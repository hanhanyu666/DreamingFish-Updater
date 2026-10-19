//! Calls current production migration/config code on newly generated fixtures only.
#![allow(dead_code)]
#[path = "../../../../admin-desktop/src-tauri/src/profiles.rs"]
mod profiles;
#[path = "../../../../admin-desktop/src-tauri/src/migrate.rs"]
mod migrate;

use std::{fs, path::{Path, PathBuf}};

fn fixture(base: &Path) -> (PathBuf, PathBuf) {
    let legacy = base.join("legacy");
    let root = base.join("program/data");
    let home = legacy.join("management");
    fs::create_dir_all(home.join("data/keys")).unwrap();
    fs::write(home.join("data/keys/demo.pk8"), b"synthetic-key-not-a-real-key").unwrap();
    let settings = serde_json::json!({"dataDirectory": home.join("data")});
    fs::write(home.join("management-settings.json"), serde_json::to_vec(&settings).unwrap()).unwrap();
    let config = serde_json::json!({"profiles": [
        {"id":"local","name":"Local","kind":"local","home":home},
        {"id":"remote","name":"Remote","kind":"remote","url":"https://example.invalid/"}
    ]});
    fs::write(legacy.join("connections.json"), serde_json::to_vec(&config).unwrap()).unwrap();
    fs::create_dir_all(&root).unwrap();
    (legacy, root)
}

fn main() {
    let base = PathBuf::from(std::env::args().nth(1).expect("fresh fixture root required"));
    assert!(base.is_absolute() && !base.exists(), "use a fresh absolute fixture directory");
    fs::create_dir_all(&base).unwrap();

    let (legacy, root) = fixture(&base.join("save-failure"));
    // Deterministic failure of the config write after the home has already been moved.
    fs::create_dir(root.join("connections.json.tmp")).unwrap();
    let notice = migrate::legacy_into(&legacy, &root).unwrap();
    let old = profiles::Config::load(&legacy).unwrap();
    let profiles::Connection::Local { home } = &old.profiles[0].connection else { panic!() };
    println!("SAVE_FAILURE original_home_exists={} moved_key_exists={} old_profile_target_exists={} notice_claims_old_location={}",
        legacy.join("management").exists(), root.join("management/data/keys/demo.pk8").exists(),
        Path::new(home).exists(), notice.contains("继续使用原位置"));
    assert!(!Path::new(home).exists());

    let (legacy, root) = fixture(&base.join("invalid-config"));
    fs::copy(legacy.join("connections.json"), legacy.join("connections.json.previous")).unwrap();
    fs::write(legacy.join("connections.json"), b"{truncated").unwrap();
    let notice = migrate::legacy_into(&legacy, &root);
    println!("INVALID_CONFIG notice={} current_profiles={} old_config_exists={} old_backup_exists={} moved_key_exists={}",
        notice.is_some(), profiles::Config::load(&root).unwrap().profiles.len(),
        legacy.join("connections.json").exists(), legacy.join("connections.json.previous").exists(),
        root.join("management/data/keys/demo.pk8").exists());
    assert!(profiles::Config::load(&root).unwrap().profiles.is_empty());

    #[cfg(windows)] {
        use std::os::windows::fs::OpenOptionsExt;
        let (legacy, root) = fixture(&base.join("busy-home"));
        let lock = legacy.join("management/desktop-backend.lock");
        fs::write(&lock, b"").unwrap();
        let held = fs::OpenOptions::new().read(true).write(true).share_mode(0).open(&lock).unwrap();
        let notice = migrate::legacy_into(&legacy, &root);
        println!("BUSY_HOME notice={} new_root_profiles={} old_root_profiles={} old_key_exists={}",
            notice.is_some(), profiles::Config::load(&root).unwrap().profiles.len(),
            profiles::Config::load(&legacy).unwrap().profiles.len(),
            legacy.join("management/data/keys/demo.pk8").exists());
        drop(held);
    }

    let (legacy, root) = fixture(&base.join("portable-move"));
    assert!(migrate::legacy_into(&legacy, &root).is_none());
    let moved = base.join("portable-move/other-location");
    // Only the generated fixture is moved, inside the fresh supplied root.
    assert!(root.starts_with(&base) && moved.starts_with(&base));
    fs::rename(&root, &moved).unwrap();
    let config = profiles::Config::load(&moved).unwrap();
    let profiles::Connection::Local { home } = &config.profiles[0].connection else { panic!() };
    let settings: serde_json::Value = serde_json::from_slice(
        &fs::read(moved.join("management/management-settings.json")).unwrap()).unwrap();
    println!("PORTABLE_MOVE profile_target_exists={} settings_target_exists={} actual_moved_key_exists={}",
        Path::new(home).exists(), Path::new(settings["dataDirectory"].as_str().unwrap()).exists(),
        moved.join("management/data/keys/demo.pk8").exists());
    println!("Fixture root: {}", base.display());
}
