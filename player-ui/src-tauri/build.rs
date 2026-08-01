fn main() {
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
