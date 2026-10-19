//! Recoverable desktop migration. The source stays authoritative until settings and
//! connection records in the verified copy have both committed. Never merge data trees.
use crate::profiles::{Config, Connection};
use serde::{Deserialize, Serialize};
use std::{fs, io::{Read, Write}, path::{Path, PathBuf}};

#[derive(Debug, PartialEq)]
pub enum MigrationStatus { Unchanged, Complete, Fallback, PendingCleanup }
#[derive(Debug)]
pub struct MigrationOutcome {
    pub root: PathBuf,
    pub status: MigrationStatus,
    pub notice: Option<String>,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Journal {
    version: u32,
    source: PathBuf,
    target: PathBuf,
    phase: String,
    config: Config,
    original_settings: Option<Vec<u8>>,
    preserve_records: bool,
}

pub fn legacy_into(legacy: &Path, root: &Path) -> MigrationOutcome {
    let fallback = |error: String| MigrationOutcome {
        root: legacy.to_path_buf(), status: MigrationStatus::Fallback,
        notice: Some(format!("迁移未提交：{error}。实际继续使用 {}；原数据已保留，重启可恢复。", legacy.display())),
    };
    if same_path(legacy, root) || (!legacy.exists() && !root.join("migration.json").exists()) {
        return MigrationOutcome { root: root.into(), status: MigrationStatus::Unchanged, notice: None };
    }
    let result = migrate(legacy, root);
    match result {
        Ok(notice) => MigrationOutcome { root: root.into(), status: if notice.is_some() { MigrationStatus::PendingCleanup } else { MigrationStatus::Complete }, notice },
        Err(error) => {
            // A saved config is the logical commit, even if writing the next journal phase failed.
            if let Ok(journal) = read_journal(root) {
                if committed(root, &journal) {
                    return MigrationOutcome { root: root.into(), status: MigrationStatus::PendingCleanup,
                        notice: Some(format!("已切换到 {}；后续整理失败：{error}。保留源目录与迁移记录。", root.display())) };
                }
            }
            fallback(error)
        }
    }
}
fn read_journal(root: &Path) -> Result<Journal, String> {
    let journal:Journal=serde_json::from_slice(&fs::read(root.join("migration.json")).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
    if journal.version!=1||!["prepared","copied","committed","cleaning","complete"].contains(&journal.phase.as_str()){return Err("迁移记录版本或阶段未知，保留双方等待核对".into());}
    Ok(journal)
}
fn save_journal(root: &Path, journal: &Journal) -> Result<(), String> {
    write_atomic(&root.join("migration.json"), &serde_json::to_vec_pretty(journal).map_err(|e| e.to_string())?)
}
fn write_atomic(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let temp = path.with_extension("writing");
    let mut file = fs::File::create(&temp).map_err(|e| format!("{}：{e}", temp.display()))?;
    file.write_all(bytes).and_then(|_| file.sync_all()).map_err(|e| e.to_string())?;
    drop(file);
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        // ReplaceFile/MoveFileEx preserves the previous complete journal until the atomic replacement.
        unsafe extern "system" { fn MoveFileExW(a: *const u16, b: *const u16, flags: u32) -> i32; }
        let a: Vec<u16> = temp.as_os_str().encode_wide().chain(Some(0)).collect();
        let b: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
        if unsafe { MoveFileExW(a.as_ptr(), b.as_ptr(), 1 | 8) } == 0 { return Err(std::io::Error::last_os_error().to_string()); }
    }
    #[cfg(not(windows))]
    { fs::rename(&temp, path).map_err(|e| e.to_string())?; fs::File::open(path.parent().unwrap()).and_then(|f| f.sync_all()).map_err(|e| e.to_string())?; }
    Ok(())
}
fn checkpoint(_phase: &str) {
    #[cfg(test)]
    if std::env::var("DFS_MIGRATION_EXIT_AT").ok().as_deref() == Some(_phase) { std::process::exit(73); }
}
fn migrate(legacy: &Path, root: &Path) -> Result<Option<String>, String> {
    safe_path(legacy)?;
    safe_path(root)?;
    if strip(root, legacy).is_some() || strip(legacy, root).is_some() { return Err("源和目标目录不能重叠".into()); }
    fs::create_dir_all(root).map_err(|e| e.to_string())?;
    let guard = fs::OpenOptions::new().read(true).write(true).create(true).truncate(false).open(root.join("migration.lock")).map_err(|e| e.to_string())?;
    guard.try_lock().map_err(|e| format!("另一实例正在迁移：{e}"))?;
    let saved_journal=if root.join("migration.json").exists(){
        let journal=read_journal(root)?;
        if !same_path(&journal.source,legacy)||!same_path(journal.target.parent().ok_or("目标无父目录")?,root){return Err("迁移记录身份不匹配，需人工检查".into());}
        Some(journal)
    }else{None};
    let old_home = legacy.join("management");
    let _data_guard = if old_home.is_dir() {
        let file = fs::OpenOptions::new().read(true).write(true).create(true).truncate(false).open(old_home.join("desktop-backend.lock")).map_err(|e| e.to_string())?;
        file.try_lock().map_err(|e| format!("管理数据正在使用：{e}"))?;
        Some(file)
    } else { None };
    let data_root=if old_home.is_dir(){
        let settings=old_home.join("management-settings.json");
        let settings_bytes=if settings.try_exists().map_err(|e|e.to_string())?{Some(fs::read(&settings).map_err(|e|e.to_string())?)}else{saved_journal.as_ref().and_then(|journal|journal.original_settings.clone())};
        let configured=if let Some(bytes)=settings_bytes{
            let value:serde_json::Value=serde_json::from_slice(&bytes).map_err(|e|format!("管理设置损坏：{e}"))?;
            match value.get("dataDirectory"){None=>"data".to_string(),Some(serde_json::Value::String(path)) if !path.trim().is_empty()=>path.clone(),_=>return Err("管理数据位置无效，保留源目录".into())}
        }else{"data".into()};
        let configured=PathBuf::from(configured);let resolved=if configured.is_absolute(){configured}else{old_home.join(configured)};
        safe_path(&resolved)?;Some(resolved)
    }else{None};
    let data_lock=|suffix:&str|->Result<Option<fs::File>,String>{
        let Some(data)=&data_root else{return Ok(None)};
        let parent=data.parent().ok_or("管理数据目录没有父目录")?;
        let name=data.file_name().ok_or("管理数据目录不能是磁盘根")?.to_string_lossy();
        let file=fs::OpenOptions::new().read(true).write(true).create(true).truncate(false).open(parent.join(format!(".{name}.{suffix}.lock"))).map_err(|e|e.to_string())?;
        file.try_lock().map_err(|e|format!("管理核心仍在使用 {}：{e}",data.display()))?;Ok(Some(file))
    };
    let _tasks_guard=data_lock("tasks")?;
    let _instance_guard = if old_home.is_dir() {
        data_lock("maintenance")?
    }else{None};
    let mut journal = if let Some(journal)=saved_journal {
        journal
    } else {
        let (mut config, _) = Config::load_with_notice(root)?;
        let (old, recovered) = Config::load_with_notice(legacy)?;
        let target = free_home(root);
        let mut incoming=old.profiles;
        for profile in config.profiles.iter_mut().chain(incoming.iter_mut()) {
            if let Connection::Local { home } = &mut profile.connection {
                if let Some(rest) = strip(Path::new(home), &old_home) { *home = if rest.as_os_str().is_empty() { target.clone() } else { target.join(rest) }.to_string_lossy().into(); profile.relocation_required = false; }
            }
        }
        for mut profile in incoming {
            if let Some(existing)=config.profiles.iter().find(|p|p.id==profile.id) {
                if serde_json::to_value(existing).map_err(|e|e.to_string())?==serde_json::to_value(&profile).map_err(|e|e.to_string())?{continue;}
                // A copied portable configuration can legitimately share a connection ID.
                // Preserve both records; this ID is not a project's signing identity.
                let prefix:String=profile.id.chars().take(32).collect();
                profile.id=(1usize..).map(|n|format!("{prefix}-migrated-{n}")).find(|id|!config.profiles.iter().any(|p|&p.id==id)).ok_or("无法分配迁移连接身份")?;
            }
            config.profiles.push(profile);
        }
        let settings = old_home.join("management-settings.json");
        let original_settings = if settings.try_exists().map_err(|e| e.to_string())? { Some(fs::read(&settings).map_err(|e| e.to_string())?) } else { None };
        // Parse before the first copy; malformed settings must not be silently ignored.
        if let Some(bytes) = &original_settings { serde_json::from_slice::<serde_json::Value>(bytes).map_err(|e| format!("管理设置损坏：{e}"))?; }
        let j = Journal { version: 1, source: legacy.into(), target, phase: "prepared".into(), config, original_settings, preserve_records: recovered.is_some() };
        save_journal(root, &j)?; checkpoint("prepared");
        j
    };
    if journal.phase == "complete" { return Ok(if legacy.exists() { Some(format!("实际使用 {}；旧目录 {} 有保留材料，未自动删除。", root.display(), legacy.display())) } else { None }); }
    if journal.phase == "prepared" {
        if old_home.is_dir() { copy_tree(&old_home, &journal.target)?; same_tree(&old_home, &journal.target, false)?; }
        journal.phase = "copied".into(); save_journal(root, &journal)?; checkpoint("copied");
    }
    if journal.phase == "copied" {
        if old_home.is_dir() { same_tree(&old_home, &journal.target, true)?; }
        if let Some(bytes)=&journal.original_settings {
            if old_home.is_dir()&&fs::read(old_home.join("management-settings.json")).map_err(|e|e.to_string())?!=*bytes{return Err("源管理设置已改变，保留双方并等待人工核对".into());}
        }
        if let Some(bytes) = &journal.original_settings {
            let mut settings: serde_json::Value = serde_json::from_slice(bytes).map_err(|e| e.to_string())?;
            if let Some(rest) = settings.get("dataDirectory").and_then(|v| v.as_str()).and_then(|v| strip(Path::new(v), &old_home)) {
                settings["dataDirectory"] = journal.target.join(rest).to_string_lossy().into();
            }
            write_atomic(&journal.target.join("management-settings.json"), &serde_json::to_vec_pretty(&settings).map_err(|e| e.to_string())?)?;
        }
        checkpoint("settings");
        journal.config.save(root)?; checkpoint("config");
        journal.phase = "committed".into(); save_journal(root, &journal)?; checkpoint("committed");
    }
    if !committed(root, &journal) { return Err("连接提交校验不一致，保留全部数据".into()); }
    if old_home.is_dir() {
        same_tree(&old_home, &journal.target, true)?;
        if let Some(bytes) = &journal.original_settings {
            match fs::read(old_home.join("management-settings.json")) {
                Ok(current) if current==*bytes=>(),
                Err(error) if error.kind()==std::io::ErrorKind::NotFound&&journal.phase=="cleaning"=>(),
                _=>return Err("原管理设置已被修改，保留源目录".into()),
            }
        }
        journal.phase="cleaning".into();save_journal(root,&journal)?;checkpoint("cleaning");
        // Both absolute boundaries and all reparse points have been checked; the journal owns this copy.
        safe_tree(&old_home)?;
        fs::remove_dir_all(&old_home).map_err(|e| format!("旧管理数据待清理：{e}"))?;
    }
    checkpoint("source_removed");
    let logs = legacy.join("logs");
    if logs.is_dir() { let target = root.join("logs/previous"); copy_tree(&logs, &target)?; same_tree(&logs, &target, false)?; safe_tree(&logs)?; fs::remove_dir_all(logs).map_err(|e| e.to_string())?; }
    if !journal.preserve_records {
        for name in ["connections.json", "connections.json.previous", "connections.json.tmp"] {
            let path = legacy.join(name); if path.is_file() { fs::remove_file(path).map_err(|e| e.to_string())?; }
        }
    }
    for name in ["EBWebView", "webview", "webviews"] {
        let path = legacy.join(name);
        if path.exists() { safe_tree(&path)?; fs::remove_dir_all(path).map_err(|e| e.to_string())?; }
    }
    journal.phase = "complete".into(); save_journal(root, &journal)?;
    match fs::remove_dir(legacy) {
        Ok(()) => Ok(None),
        Err(error) => Ok(Some(format!("实际使用 {}；旧目录 {} 仍保留文件（{error}）。{}", root.display(), legacy.display(), if journal.preserve_records { "损坏原件和有效 previous 已保留作为恢复证据。" } else { "请核对后手动清理。" })))
    }
}
fn committed(root: &Path, journal: &Journal) -> bool {
    if journal.phase == "prepared" { return false; }
    let Ok(cfg) = Config::load(root) else { return false };
    let mut expected=journal.config.clone();
    for profile in &mut expected.profiles {if let Connection::Local{home}=&mut profile.connection{let Ok(resolved)=crate::profiles::resolve_home(root,home)else{return false;};*home=resolved.to_string_lossy().into();}}
    serde_json::to_value(&cfg).ok()==serde_json::to_value(&expected).ok()
}
fn free_home(root: &Path) -> PathBuf {
    (0..).map(|n| root.join(if n == 0 { "management".into() } else { format!("management-previous-{n}") })).find(|p| !p.exists()).unwrap()
}
fn linked(meta: &fs::Metadata) -> bool {
    #[cfg(windows)] { use std::os::windows::fs::MetadataExt; meta.file_attributes() & 0x400 != 0 }
    #[cfg(not(windows))] { meta.file_type().is_symlink() }
}
fn safe_path(path: &Path) -> Result<(), String> {
    if !path.is_absolute() || path.components().any(|c| matches!(c, std::path::Component::ParentDir)) { return Err("迁移路径必须为不含上级引用的绝对路径".into()); }
    for ancestor in path.ancestors() {
        match fs::symlink_metadata(ancestor) { Ok(meta) if linked(&meta) => return Err(format!("{} 是链接或 junction，拒绝自动迁移", ancestor.display())), Ok(_) => (), Err(e) if e.kind() == std::io::ErrorKind::NotFound => (), Err(e) => return Err(e.to_string()) }
    }
    Ok(())
}
fn safe_tree(path: &Path) -> Result<(), String> {
    safe_path(path)?;
    for e in fs::read_dir(path).map_err(|e| e.to_string())? {
        let e = e.map_err(|e| e.to_string())?;
        let meta = fs::symlink_metadata(e.path()).map_err(|e| e.to_string())?;
        if linked(&meta) { return Err(format!("{} 是链接，保留原目录", e.path().display())); }
        if meta.is_dir() { safe_tree(&e.path())?; }
    }
    Ok(())
}
fn equal_files(a: &Path, b: &Path) -> Result<bool, String> {
    let mut a = fs::File::open(a).map_err(|e| e.to_string())?;
    let mut b = fs::File::open(b).map_err(|e| e.to_string())?;
    let (mut x, mut y) = ([0u8; 65536], [0u8; 65536]);
    loop { let n = a.read(&mut x).map_err(|e| e.to_string())?; let m = b.read(&mut y).map_err(|e| e.to_string())?; if n != m || x[..n] != y[..m] { return Ok(false); } if n == 0 { return Ok(true); } }
}
fn copy_tree(source: &Path, target: &Path) -> Result<(), String> {
    safe_tree(source)?; safe_path(target)?;
    fs::create_dir_all(target).map_err(|e| e.to_string())?;
    for e in fs::read_dir(source).map_err(|e| e.to_string())? {
        let e = e.map_err(|e| e.to_string())?;
        let destination = target.join(e.file_name());
        if e.file_name() == "desktop-backend.lock" || (e.file_name().to_string_lossy().ends_with(".maintenance.lock")||e.file_name().to_string_lossy().ends_with(".tasks.lock")) { continue; }
        if e.file_type().map_err(|e| e.to_string())?.is_dir() { copy_tree(&e.path(), &destination)?; }
        else if destination.exists() { if !equal_files(&e.path(), &destination)? { return Err(format!("{} 与源内容不同，保留双方", destination.display())); } }
        else { fs::copy(e.path(), &destination).map_err(|e| e.to_string())?; fs::OpenOptions::new().write(true).open(&destination).and_then(|f| f.sync_all()).map_err(|e| e.to_string())?; }
    }
    Ok(())
}
fn same_tree(source: &Path, target: &Path, skip_settings: bool) -> Result<(), String> {
    safe_tree(source)?; safe_tree(target)?;
    for e in fs::read_dir(source).map_err(|e| e.to_string())? {
        let e = e.map_err(|e| e.to_string())?;
        if e.file_name() == "desktop-backend.lock" || (e.file_name().to_string_lossy().ends_with(".maintenance.lock")||e.file_name().to_string_lossy().ends_with(".tasks.lock")) || (skip_settings && e.file_name() == "management-settings.json") { continue; }
        let dest = target.join(e.file_name());
        if e.file_type().map_err(|e| e.to_string())?.is_dir() { same_tree(&e.path(), &dest, false)?; }
        else if !equal_files(&e.path(), &dest)? { return Err(format!("{} 复制后内容不一致", dest.display())); }
    }
    Ok(())
}
fn same_path(a: &Path, b: &Path) -> bool {
    let a = dunce::canonicalize(a).unwrap_or_else(|_| a.into());
    let b = dunce::canonicalize(b).unwrap_or_else(|_| b.into());
    a.to_string_lossy().eq_ignore_ascii_case(&b.to_string_lossy())
}
fn strip(path: &Path, base: &Path) -> Option<PathBuf> {
    let mut rest = path.components();
    for part in base.components() { if !rest.next()?.as_os_str().to_string_lossy().eq_ignore_ascii_case(&part.as_os_str().to_string_lossy()) { return None; } }
    Some(rest.as_path().into())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture(name: &str) -> (PathBuf, PathBuf, PathBuf) {
        let base = std::env::temp_dir().join(format!("dfs-remediation-{name}-{}", std::process::id()));
        assert!(!base.exists(), "use a fresh fixture root");
        let old = base.join("legacy"); let new = base.join("new/data");
        fs::create_dir_all(old.join("management/data/keys")).unwrap();
        fs::write(old.join("management/data/keys/test.pk8"), b"synthetic-key").unwrap();
        fs::write(old.join("management/data/management.db"), b"synthetic-db").unwrap();
        fs::write(old.join("management/management-settings.json"), serde_json::to_vec(&serde_json::json!({"dataDirectory":old.join("management/data")})).unwrap()).unwrap();
        fs::write(old.join("connections.json"), serde_json::to_vec(&serde_json::json!({"profiles":[{"id":"test","name":"Test","kind":"local","home":old.join("management")}]})).unwrap()).unwrap();
        (base,old,new)
    }
    #[test]
    fn failed_config_commit_uses_original_and_restart_resumes() {
        let (base,old,new) = fixture("config");
        fs::create_dir_all(new.join("connections.json.tmp")).unwrap();
        let result = legacy_into(&old,&new);
        assert_eq!(result.root,old); assert_eq!(result.status,MigrationStatus::Fallback);
        assert!(old.join("management/data/keys/test.pk8").is_file());
        fs::remove_dir(new.join("connections.json.tmp")).unwrap();
        let result = legacy_into(&old,&new); assert_eq!(result.root,new,"{result:?}"); assert_eq!(result.status,MigrationStatus::Complete);
        assert_eq!(fs::read(new.join("management/data/keys/test.pk8")).unwrap(),b"synthetic-key");
        fs::remove_dir_all(base).unwrap();
    }
    #[test]
    fn corrupt_primary_recovers_previous_and_keeps_evidence() {
        let (base,old,new) = fixture("corrupt");
        fs::copy(old.join("connections.json"),old.join("connections.json.previous")).unwrap();
        fs::write(old.join("connections.json"),b"{").unwrap();
        let result=legacy_into(&old,&new); assert_eq!(result.status,MigrationStatus::PendingCleanup,"{result:?}");
        assert_eq!(Config::load(&new).unwrap().profiles.len(),1);
        assert_eq!(fs::read(old.join("connections.json")).unwrap(),b"{");
        assert!(old.join("connections.json.previous").is_file()); fs::remove_dir_all(base).unwrap();
    }
    #[test]
    fn both_configs_corrupt_do_not_move_data() {
        let (base,old,new)=fixture("both"); fs::write(old.join("connections.json"),b"{").unwrap();
        assert_eq!(legacy_into(&old,&new).root,old); assert!(!new.join("management").exists()); fs::remove_dir_all(base).unwrap();
    }
    #[test]
    fn configured_data_directory_uses_its_actual_maintenance_lock() {
        let (base,old,new)=fixture("custom-lock");let home=old.join("management");
        fs::rename(home.join("data"),home.join("publishing-data")).unwrap();
        fs::write(home.join("management-settings.json"),br#"{"dataDirectory":"publishing-data"}"#).unwrap();
        let held=fs::OpenOptions::new().read(true).write(true).create(true).truncate(false).open(home.join(".publishing-data.maintenance.lock")).unwrap();held.try_lock_shared().unwrap();
        let blocked=legacy_into(&old,&new);assert_eq!(blocked.root,old);assert_eq!(blocked.status,MigrationStatus::Fallback);assert!(home.join("publishing-data/management.db").is_file());
        drop(held);let copied=legacy_into(&old,&new);assert_eq!(copied.root,new,"{copied:?}");assert_eq!(copied.status,MigrationStatus::Complete);
        assert_eq!(fs::read(new.join("management/publishing-data/keys/test.pk8")).unwrap(),b"synthetic-key");fs::remove_dir_all(base).unwrap();
    }
    #[test]
    fn an_existing_target_with_the_same_connection_id_keeps_both_projects() {
        let (base,old,new)=fixture("id-collision");let existing=new.join("management");
        fs::create_dir_all(existing.join("data")).unwrap();fs::write(existing.join("data/management.db"),b"existing-project").unwrap();
        fs::write(new.join("connections.json"),serde_json::to_vec(&serde_json::json!({"profiles":[{"id":"test","name":"Existing","kind":"local","home":existing}]})).unwrap()).unwrap();
        let result=legacy_into(&old,&new);assert_eq!(result.status,MigrationStatus::Complete,"{result:?}");
        let config=Config::load(&new).unwrap();assert_eq!(config.profiles.len(),2);assert_ne!(config.profiles[0].id,config.profiles[1].id);
        assert_eq!(fs::read(existing.join("data/management.db")).unwrap(),b"existing-project");
        let imported=config.profiles.iter().find(|p|p.name=="Test").unwrap();
        assert!(matches!(&imported.connection,Connection::Local{home} if fs::read(Path::new(home).join("data/keys/test.pk8")).unwrap()==b"synthetic-key"));
        fs::remove_dir_all(base).unwrap();
    }
    #[test]
    fn partial_cleanup_without_source_settings_resumes_and_keeps_the_actual_data_lock() {
        let (base,old,new)=fixture("partial-cleanup");let home=old.join("management");
        fs::rename(home.join("data"),home.join("publishing-data")).unwrap();
        fs::write(home.join("management-settings.json"),br#"{"dataDirectory":"publishing-data"}"#).unwrap();
        let exited=std::process::Command::new(std::env::current_exe().unwrap()).args(["--exact","migrate::tests::subprocess_worker","--nocapture"])
            .env("DFS_MIGRATION_FIXTURE",&base).env("DFS_MIGRATION_EXIT_AT","cleaning").output().unwrap();assert_eq!(exited.status.code(),Some(73));
        fs::remove_file(home.join("management-settings.json")).unwrap();fs::remove_file(home.join("publishing-data/keys/test.pk8")).unwrap();
        let held=fs::OpenOptions::new().read(true).write(true).open(home.join(".publishing-data.tasks.lock")).unwrap();held.try_lock_shared().unwrap();
        let blocked=legacy_into(&old,&new);assert_eq!(blocked.root,new);assert_eq!(blocked.status,MigrationStatus::PendingCleanup);assert!(home.join("publishing-data/management.db").is_file());
        drop(held);let resumed=legacy_into(&old,&new);assert_eq!(resumed.status,MigrationStatus::Complete,"{resumed:?}");
        assert_eq!(fs::read(new.join("management/publishing-data/keys/test.pk8")).unwrap(),b"synthetic-key");fs::remove_dir_all(base).unwrap();
    }
    #[test]
    fn portable_config_resolves_current_copy_and_keeps_external_home() {
        let (base,old,new)=fixture("portable"); assert_eq!(legacy_into(&old,&new).root,new);
        let moved=base.join("other/data"); copy_tree(&new,&moved).unwrap();
        let cfg=Config::load(&moved).unwrap();
        assert!(matches!(&cfg.profiles[0].connection,Connection::Local {home} if Path::new(home)==moved.join("management")));
        fs::remove_dir_all(base).unwrap();
    }
    #[test]
    fn subprocess_worker() {
        if let Ok(base) = std::env::var("DFS_MIGRATION_FIXTURE") {
            let base = PathBuf::from(base); let _ = legacy_into(&base.join("legacy"), &base.join("new/data"));
        }
    }
    #[test]
    fn process_exit_at_each_commit_boundary_is_recoverable() {
        for phase in ["prepared","copied","settings","config","committed","cleaning","source_removed"] {
            let (base,old,new) = fixture(&format!("exit-{phase}"));
            let exit=std::process::Command::new(std::env::current_exe().unwrap())
                .args(["--exact","migrate::tests::subprocess_worker","--nocapture"])
                .env("DFS_MIGRATION_FIXTURE",&base).env("DFS_MIGRATION_EXIT_AT",phase).output().unwrap();
            assert_eq!(exit.status.code(),Some(73),"{phase}: {}",String::from_utf8_lossy(&exit.stdout));
            let result=legacy_into(&old,&new); assert_eq!(result.root,new,"{phase}: {result:?}");
            assert_eq!(fs::read(new.join("management/data/keys/test.pk8")).unwrap(),b"synthetic-key");
            assert_eq!(Config::load(&new).unwrap().profiles.len(),1); fs::remove_dir_all(base).unwrap();
        }
    }
    #[test]
    fn copy_verification_compares_content_and_rejects_overlap() {
        let (base,old,new)=fixture("verify");
        fs::create_dir_all(&new).unwrap(); fs::write(new.join("a"),b"evil").unwrap(); fs::write(old.join("a"),b"good").unwrap();
        assert!(!equal_files(&old.join("a"),&new.join("a")).unwrap());
        assert_eq!(legacy_into(&old,&old.join("child")).status,MigrationStatus::Fallback); fs::remove_dir_all(base).unwrap();
    }
}
