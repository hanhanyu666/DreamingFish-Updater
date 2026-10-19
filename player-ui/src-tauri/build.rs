fn main() {
    println!("cargo:rerun-if-changed=../dist/build-info.json");
    if std::env::var_os("CARGO_FEATURE_CUSTOM_PROTOCOL").is_some() {
        let checker = "../../packaging/check-build-resources.mjs";
        let component = "player-ui";
        let status=std::process::Command::new("node").args([checker,component]).status().expect("Node.js is required to verify fresh frontend resources");
        assert!(status.success(),"Frontend resources are missing or stale; run the unified validation/build entry first");
        let marker=std::fs::read_to_string("../dist/build-info.json").expect("frontend build marker");
        let id=marker.lines().find(|line|line.trim_start().starts_with("\"buildId\":" )).and_then(|line|line.split('"').nth(3)).expect("frontend source identity");
        println!("cargo:rustc-env=DFS_BUILD_ID={id}");
    }

    #[cfg(target_os = "windows")]
    {
        let mut windows = tauri_build::WindowsAttributes::new();
        // Explicit `asInvoker` execution level: without it, Windows UAC installer
        // detection flags the "DreamingFishUpdater.exe" name/version resources as
        // elevation-required and non-elevated CreateProcess fails with error 740.
        windows = windows.app_manifest(include_str!("app.manifest"));
        let attrs = tauri_build::Attributes::new().windows_attributes(windows);
        tauri_build::try_build(attrs).expect("failed to run tauri build script");
    }
    #[cfg(not(target_os = "windows"))]
    tauri_build::build();
}
