//! Versions before 0.2.4 kept connection records, logs, the default management home and
//! webview caches under %LOCALAPPDATA%\cn.dreamingfish.admin.desktop. This moves all of it into
//! the data folder beside the program and removes the old folder, so nothing is left behind in
//! AppData. A failed move keeps the old location untouched and in use.

use crate::profiles::{Config, Connection};
use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
};

/// Returns a notice for the connection page when the old data could not be moved.
pub fn legacy_into(legacy: &Path, root: &Path) -> Option<String> {
    if !legacy.is_dir() || same_path(legacy, root) {
        return None;
    }
    let mut log = Vec::new();
    let result = migrate(legacy, root, &mut log);
    if let Err(error) = &result {
        log.push(format!("迁移中止：{error}"));
    }
    write_log(root, &log);
    result.err().map(|error| {
        format!(
            "旧版本的数据没能迁移到 {}：{error}。暂时继续使用原位置，关闭占用这些文件的程序后重新打开桌面端会再次尝试。",
            root.display()
        )
    })
}

fn migrate(legacy: &Path, root: &Path, log: &mut Vec<String>) -> Result<(), String> {
    fs::create_dir_all(root).map_err(|e| e.to_string())?;
    let mut config = if root.join("connections.json").exists() {
        Config::load(root)?
    } else {
        Config::default()
    };
    if let Ok(old) = Config::load(legacy) {
        for profile in old.profiles {
            if !config.profiles.iter().any(|p| p.id == profile.id) {
                log.push(format!("沿用连接记录：{}", profile.name));
                config.profiles.push(profile);
            }
        }
    }

    let old_home = legacy.join("management");
    if old_home.is_dir() {
        let new_home = free_home(root);
        move_dir(&old_home, &new_home)?;
        log.push(format!("管理数据目录：{} → {}", old_home.display(), new_home.display()));
        rewrite_data_directory(&new_home, &old_home, log);
        for profile in &mut config.profiles {
            if let Connection::Local { home } = &mut profile.connection {
                if let Some(rest) = strip(Path::new(home), &old_home) {
                    let moved = if rest.as_os_str().is_empty() { new_home.clone() } else { new_home.join(rest) };
                    *home = moved.to_string_lossy().into();
                }
            }
        }
    }
    config.save(root)?;

    let old_logs = legacy.join("logs");
    if old_logs.is_dir() {
        let target = root.join("logs").join("previous");
        fs::create_dir_all(&target).map_err(|e| e.to_string())?;
        for entry in fs::read_dir(&old_logs).map_err(|e| e.to_string())?.flatten() {
            let destination = target.join(entry.file_name());
            if !destination.exists() {
                let _ = move_file(&entry.path(), &destination);
            }
        }
        let _ = fs::remove_dir(&old_logs);
    }

    // Records were saved above; only caches and superseded copies remain in the old folder.
    for name in ["connections.json", "connections.json.previous", "connections.json.tmp"] {
        let _ = fs::remove_file(legacy.join(name));
    }
    for cache in ["EBWebView", "webview", "webviews"] {
        let _ = fs::remove_dir_all(legacy.join(cache));
    }
    match fs::remove_dir(legacy) {
        Ok(()) => log.push(format!("已删除旧目录 {}", legacy.display())),
        Err(_) => log.push(format!("旧目录 {} 里还有无法删除的文件，已保留", legacy.display())),
    }
    Ok(())
}

/// The management service records its data folder as an absolute path; point it at the new home.
fn rewrite_data_directory(home: &Path, old_home: &Path, log: &mut Vec<String>) {
    let file = home.join("management-settings.json");
    let Ok(text) = fs::read_to_string(&file) else { return };
    let Ok(mut settings) = serde_json::from_str::<serde_json::Value>(&text) else { return };
    let Some(rest) = settings
        .get("dataDirectory")
        .and_then(|v| v.as_str())
        .and_then(|current| strip(Path::new(current), old_home))
    else {
        return;
    };
    let moved = if rest.as_os_str().is_empty() { home.to_path_buf() } else { home.join(rest) };
    settings["dataDirectory"] = moved.to_string_lossy().into();
    if let Ok(bytes) = serde_json::to_vec_pretty(&settings) {
        if fs::write(&file, bytes).is_ok() {
            log.push(format!("管理设置中的数据目录改为 {}", moved.display()));
        }
    }
}

/// The default home when it is unused, otherwise a sibling, so existing data is never merged.
fn free_home(root: &Path) -> PathBuf {
    let home = root.join("management");
    if !home.exists() || fs::read_dir(&home).map(|mut d| d.next().is_none()).unwrap_or(false) {
        return home;
    }
    (1..)
        .map(|n| root.join(if n == 1 { "management-previous".into() } else { format!("management-previous-{n}") }))
        .find(|p| !p.exists())
        .expect("unbounded candidates")
}

/// Renames on the same volume; across volumes copies everything, checks it, then deletes the source.
/// A folder still in use by a running management service cannot be renamed and is left alone.
fn move_dir(source: &Path, target: &Path) -> Result<(), String> {
    if target.exists() {
        fs::remove_dir(target).map_err(|e| format!("无法使用 {}：{e}", target.display()))?;
    }
    match fs::rename(source, target) {
        Ok(()) => return Ok(()),
        Err(error) if !cross_volume(&error) => {
            return Err(format!("{} 正在被使用或无法移动（{error}）", source.display()))
        }
        Err(_) => {}
    }
    if let Err(error) = copy_tree(source, target).and_then(|()| same_tree(source, target)) {
        let _ = fs::remove_dir_all(target);
        return Err(format!("复制 {} 失败（{error}）", source.display()));
    }
    fs::remove_dir_all(source).map_err(|e| format!("已复制，但无法删除原目录：{e}"))
}

fn move_file(source: &Path, target: &Path) -> std::io::Result<()> {
    match fs::rename(source, target) {
        Ok(()) => Ok(()),
        Err(error) if cross_volume(&error) => {
            fs::copy(source, target)?;
            fs::remove_file(source)
        }
        Err(error) => Err(error),
    }
}

fn cross_volume(error: &std::io::Error) -> bool {
    // ERROR_NOT_SAME_DEVICE on Windows, EXDEV elsewhere.
    matches!(error.raw_os_error(), Some(17) | Some(18))
}

fn copy_tree(source: &Path, target: &Path) -> Result<(), String> {
    fs::create_dir_all(target).map_err(|e| e.to_string())?;
    for entry in fs::read_dir(source).map_err(|e| e.to_string())? {
        let entry = entry.map_err(|e| e.to_string())?;
        let kind = entry.file_type().map_err(|e| e.to_string())?;
        let destination = target.join(entry.file_name());
        if kind.is_symlink() {
            return Err(format!("{} 是链接，不能自动迁移", entry.path().display()));
        } else if kind.is_dir() {
            copy_tree(&entry.path(), &destination)?;
        } else {
            fs::copy(entry.path(), &destination).map_err(|e| e.to_string())?;
        }
    }
    Ok(())
}

fn same_tree(source: &Path, target: &Path) -> Result<(), String> {
    for entry in fs::read_dir(source).map_err(|e| e.to_string())? {
        let entry = entry.map_err(|e| e.to_string())?;
        let destination = target.join(entry.file_name());
        let metadata = entry.metadata().map_err(|e| e.to_string())?;
        if metadata.is_dir() {
            same_tree(&entry.path(), &destination)?;
        } else if fs::metadata(&destination).map(|m| m.len()).ok() != Some(metadata.len()) {
            return Err(format!("{} 复制后大小不一致", destination.display()));
        }
    }
    Ok(())
}

fn same_path(a: &Path, b: &Path) -> bool {
    let a = dunce::canonicalize(a).unwrap_or_else(|_| a.to_path_buf());
    let b = dunce::canonicalize(b).unwrap_or_else(|_| b.to_path_buf());
    a.to_string_lossy().eq_ignore_ascii_case(&b.to_string_lossy())
}

/// The part of `path` below `base`, compared case-insensitively as Windows paths are.
fn strip<'a>(path: &'a Path, base: &Path) -> Option<PathBuf> {
    let mut rest = path.components();
    for part in base.components() {
        let next = rest.next()?;
        if !next.as_os_str().to_string_lossy().eq_ignore_ascii_case(&part.as_os_str().to_string_lossy()) {
            return None;
        }
    }
    Some(rest.as_path().to_path_buf())
}

fn write_log(root: &Path, lines: &[String]) {
    if lines.is_empty() || fs::create_dir_all(root.join("logs")).is_err() {
        return;
    }
    if let Ok(mut file) = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(root.join("logs").join("migration.log"))
    {
        for line in lines {
            let _ = writeln!(file, "{line}");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp(name: &str) -> PathBuf {
        let root = std::env::temp_dir().join(format!("dfs-migrate-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        root
    }

    fn legacy_with_home(base: &Path) -> PathBuf {
        let legacy = base.join("legacy");
        let home = legacy.join("management");
        fs::create_dir_all(home.join("data").join("keys")).unwrap();
        fs::write(home.join("data").join("keys").join("demo.pk8"), b"secret-key").unwrap();
        let settings = serde_json::json!({"dataDirectory": home.join("data").to_string_lossy(), "httpPort": 8080});
        fs::write(home.join("management-settings.json"), serde_json::to_vec(&settings).unwrap()).unwrap();
        fs::create_dir_all(legacy.join("logs")).unwrap();
        fs::write(legacy.join("logs").join("a.log"), b"log").unwrap();
        fs::create_dir_all(legacy.join("EBWebView").join("Default")).unwrap();
        fs::write(legacy.join("EBWebView").join("Default").join("cache"), b"x").unwrap();
        let config = format!(
            r#"{{"profiles":[{{"id":"old","name":"本机管理","kind":"local","home":{}}},{{"id":"custom","name":"其他","kind":"local","home":"D:\\Other"}}]}}"#,
            serde_json::to_string(&home.to_string_lossy()).unwrap()
        );
        fs::write(legacy.join("connections.json"), config).unwrap();
        legacy
    }

    #[test]
    fn moves_everything_and_removes_the_old_folder() {
        let base = temp("all");
        let legacy = legacy_with_home(&base);
        let root = base.join("program").join("data");

        assert_eq!(legacy_into(&legacy, &root), None);

        assert!(!legacy.exists());
        assert_eq!(fs::read(root.join("management/data/keys/demo.pk8")).unwrap(), b"secret-key");
        assert_eq!(fs::read(root.join("logs/previous/a.log")).unwrap(), b"log");
        let settings: serde_json::Value =
            serde_json::from_slice(&fs::read(root.join("management/management-settings.json")).unwrap()).unwrap();
        assert_eq!(settings["dataDirectory"], root.join("management").join("data").to_string_lossy().as_ref());
        assert_eq!(settings["httpPort"], 8080);
        let config = Config::load(&root).unwrap();
        let homes: Vec<_> = config
            .profiles
            .iter()
            .map(|p| match &p.connection {
                Connection::Local { home } => home.clone(),
                _ => String::new(),
            })
            .collect();
        assert_eq!(homes[0], root.join("management").to_string_lossy());
        assert_eq!(homes[1], "D:\\Other");
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn keeps_a_used_home_and_records_copied_by_0_2_3() {
        let base = temp("used");
        let legacy = legacy_with_home(&base);
        let root = base.join("program").join("data");
        fs::create_dir_all(root.join("management")).unwrap();
        fs::write(root.join("management").join("management-settings.json"), b"new").unwrap();
        fs::copy(legacy.join("connections.json"), root.join("connections.json")).unwrap();

        assert_eq!(legacy_into(&legacy, &root), None);

        assert_eq!(fs::read(root.join("management/management-settings.json")).unwrap(), b"new");
        assert!(root.join("management-previous/data/keys/demo.pk8").is_file());
        let config = Config::load(&root).unwrap();
        assert_eq!(config.profiles.len(), 2);
        assert!(matches!(&config.profiles[0].connection,
            Connection::Local { home } if home == &root.join("management-previous").to_string_lossy()));
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn nothing_to_do_without_an_old_folder() {
        let base = temp("none");
        assert_eq!(legacy_into(&base.join("missing"), &base.join("data")), None);
        assert!(!base.join("data").exists());
    }
}
