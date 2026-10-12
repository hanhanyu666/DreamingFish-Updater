use tauri::WebviewWindow;
use url::{Origin, Url};

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Action {
    Status,
    Drag,
    Minimize,
    Maximize,
    Close,
    Connections,
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FrameState {
    pub maximized: bool,
}

pub fn bundled_launcher(label: &str, url: &Url) -> bool {
    label == "main"
        && (url.port().is_none()
            && (url.scheme() == "tauri" && url.host_str() == Some("localhost")
                || url.scheme() == "http" && url.host_str() == Some("tauri.localhost"))
            || cfg!(debug_assertions)
                && url.scheme() == "http"
                && url.host_str() == Some("127.0.0.1")
                && url.port() == Some(1421))
}

pub fn allowed(label: &str, url: &Url, active_origin: Option<&Origin>) -> bool {
    bundled_launcher(label, url)
        || (label == "workspace" && active_origin.is_some_and(|origin| *origin == url.origin()))
}

pub fn operate(window: &WebviewWindow, action: Action) -> Result<FrameState, String> {
    let result = match action {
        Action::Status | Action::Connections => Ok(()),
        Action::Drag => window.start_dragging(),
        Action::Minimize => window.minimize(),
        Action::Maximize => {
            if window.is_maximized().map_err(|e| e.to_string())? {
                window.unmaximize()
            } else {
                window.maximize()
            }
        }
        Action::Close => window.hide(),
    };
    result.map_err(|e| e.to_string())?;
    Ok(FrameState {
        maximized: window.is_maximized().map_err(|e| e.to_string())?,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn only_bundled_launcher_can_manage_connections() {
        assert!(bundled_launcher(
            "main",
            &Url::parse("http://tauri.localhost/").unwrap()
        ));
        assert!(bundled_launcher(
            "main",
            &Url::parse("tauri://localhost/").unwrap()
        ));
        assert!(!bundled_launcher(
            "workspace",
            &Url::parse("http://tauri.localhost/").unwrap()
        ));
        assert!(!bundled_launcher(
            "main",
            &Url::parse("https://example.com/").unwrap()
        ));
        assert!(!bundled_launcher(
            "main",
            &Url::parse("http://tauri.localhost:9999/").unwrap()
        ));
    }
    #[test]
    fn remote_frame_is_limited_to_the_active_workspace_origin() {
        let address = Url::parse("https://admin.example.com/").unwrap();
        let origin = address.origin();
        assert!(allowed("workspace", &address, Some(&origin)));
        assert!(!allowed("workspace", &address, None));
        assert!(!allowed("another-window", &address, Some(&origin)));
        assert!(!allowed(
            "workspace",
            &Url::parse("https://other.example.com/").unwrap(),
            Some(&origin)
        ));
    }
    #[test]
    fn frame_actions_have_no_filesystem_or_service_operations() {
        assert!(serde_json::from_str::<Action>("\"close\"").is_ok());
        for action in [
            "quit",
            "disconnect",
            "saveProfile",
            "readFile",
            "runCommand",
        ] {
            assert!(serde_json::from_str::<Action>(&format!("\"{action}\"")).is_err());
        }
    }
}
