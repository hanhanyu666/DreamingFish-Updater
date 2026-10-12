// The owning desktop process holds this handle. Windows cleans up its children
// even when the desktop is terminated before Rust can run a normal exit hook.
#[cfg(windows)]
pub struct Job(usize);
#[cfg(not(windows))]
pub struct Job;

impl Job {
    pub fn own(child: &mut std::process::Child) -> Result<Self, String> {
        #[cfg(windows)]
        unsafe {
            use windows_sys::Win32::{
                Foundation::{CloseHandle, HANDLE},
                System::{JobObjects::*, Threading::*},
            };
            let job = CreateJobObjectW(std::ptr::null(), std::ptr::null());
            if job.is_null() {
                let _ = child.kill();
                let _ = child.wait();
                return Err("无法创建后台进程控制组".into());
            }
            let mut limits: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
            limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            if SetInformationJobObject(
                job,
                JobObjectExtendedLimitInformation,
                &limits as *const _ as *const _,
                std::mem::size_of_val(&limits) as u32,
            ) == 0
            {
                CloseHandle(job);
                let _ = child.kill();
                let _ = child.wait();
                return Err("无法设置后台进程生命周期".into());
            }
            let process = OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE, 0, child.id());
            if process.is_null() || AssignProcessToJobObject(job, process) == 0 {
                if !process.is_null() {
                    CloseHandle(process);
                }
                CloseHandle(job);
                let _ = child.kill();
                let _ = child.wait();
                return Err("无法托管后台进程，请检查当前运行环境".into());
            }
            CloseHandle(process);
            let _: HANDLE = job;
            Ok(Self(job as usize))
        }
        #[cfg(not(windows))]
        {
            let _ = child;
            Ok(Self)
        }
    }
}

#[cfg(windows)]
impl Drop for Job {
    fn drop(&mut self) {
        unsafe {
            windows_sys::Win32::Foundation::CloseHandle(self.0 as _);
        }
    }
}

#[cfg(all(test, windows))]
mod tests {
    use super::*;
    use std::os::windows::process::CommandExt;
    fn helper() -> std::process::Child {
        std::process::Command::new("C:\\Windows\\System32\\ping.exe")
            .args(["-t", "127.0.0.1"])
            .creation_flags(0x08000000)
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
            .unwrap()
    }
    #[test]
    fn dropping_owner_cleans_its_child_and_leaves_other_processes_running() {
        let mut owned = helper();
        let job = Job::own(&mut owned).unwrap();
        let mut other = helper();
        drop(job);
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
        let mut owned_exited = false;
        while std::time::Instant::now() < deadline {
            if owned.try_wait().unwrap().is_some() {
                owned_exited = true;
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(20));
        }
        if !owned_exited {
            let _ = owned.kill();
            let _ = owned.wait();
        }
        let unaffected = other.try_wait().unwrap().is_none();
        let _ = other.kill();
        let _ = other.wait();
        assert!(owned_exited);
        assert!(unaffected);
    }
}
