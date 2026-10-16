fn main() {
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
            "check_update",
            "install_update",
            "open_release_page",
        ]),
    ))
    .expect("build desktop configuration");
}
