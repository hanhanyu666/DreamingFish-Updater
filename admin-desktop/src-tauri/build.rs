fn main() {
    println!("cargo:rerun-if-changed=../dist/build-info.json");
    if std::env::var_os("CARGO_FEATURE_CUSTOM_PROTOCOL").is_some() {
        let checker = "../../packaging/check-build-resources.mjs";
        let component = "admin-desktop";
        let status=std::process::Command::new("node").args([checker,component]).status().expect("Node.js is required to verify fresh frontend resources");
        assert!(status.success(),"Frontend resources are missing or stale; run the unified validation/build entry first");
        let marker=std::fs::read_to_string("../dist/build-info.json").expect("frontend build marker");
        let id=marker.lines().find(|line|line.trim_start().starts_with("\"buildId\":" )).and_then(|line|line.split('"').nth(3)).expect("frontend source identity");
        println!("cargo:rustc-env=DFS_BUILD_ID={id}");
    }

    tauri_build::try_build(tauri_build::Attributes::new().app_manifest(
        tauri_build::AppManifest::new().commands(&[
            "load_desktop",
            "save_profile",
            "delete_profile",
            "connect_profile",
            "cancel_connect",
            "disconnect_workspace",
            "show_workspace",
            "choose_home",
            "open_logs",
            "quit_application",
            "desktop_window_action",
            "open_data_dir",
            "dismiss_notice",
            "check_update",
            "install_update",
            "open_release_page",
        ]),
    ))
    .expect("build desktop configuration");
}
