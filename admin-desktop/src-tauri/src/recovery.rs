//! One policy for every WebView failure kind, scoped to the actual window.
use std::{collections::{HashMap,VecDeque},time::{Duration,Instant}};

#[derive(Default)]
struct Window {epoch:u64,attempts:VecDeque<Instant>,pending:bool,blocked:bool}
#[derive(Default)]
pub struct Budget {windows:HashMap<String,Window>,next_epoch:u64}
#[derive(Debug,PartialEq)]
pub enum Decision {AlreadyPending,Exhausted,Retry {attempt:usize,delay:Duration}}
impl Budget {
    pub fn attach(&mut self,label:&str)->u64 {self.next_epoch+=1;let epoch=self.next_epoch;self.windows.entry(label.into()).or_default().epoch=epoch;epoch}
    pub fn current(&self,label:&str,epoch:u64)->bool {self.windows.get(label).is_some_and(|window|window.epoch==epoch)}
    pub fn schedule(&mut self,label:&str,now:Instant)->Decision {
        let window=self.windows.entry(label.into()).or_default();
        if window.pending{return Decision::AlreadyPending;}
        if window.blocked{return Decision::Exhausted;}
        window.attempts.retain(|time|now.duration_since(*time)<Duration::from_secs(60));
        if window.attempts.len()>=3{window.blocked=true;return Decision::Exhausted;}
        window.attempts.push_back(now);window.pending=true;
        Decision::Retry {attempt:window.attempts.len(),delay:Duration::from_millis(200*(1u64<<(window.attempts.len()-1)))}
    }
    pub fn finish(&mut self,label:&str){if let Some(window)=self.windows.get_mut(label){window.pending=false;}}
    pub fn manual_reset(&mut self,label:&str){self.windows.remove(label);}
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn callbacks_from_destroyed_controllers_cannot_rebuild_a_new_window(){let mut budget=Budget::default();let old=budget.attach("workspace");assert!(budget.current("workspace",old));let new=budget.attach("workspace");assert!(!budget.current("workspace",old));assert!(budget.current("workspace",new));}
    #[test]
    fn all_faults_share_three_attempts_and_manual_recovery_remains_available(){
        let mut budget=Budget::default();let now=Instant::now();
        for _kind in ["browser","renderer","unresponsive"]{
            assert!(matches!(budget.schedule("workspace",now),Decision::Retry{..}));
            assert_eq!(budget.schedule("workspace",now),Decision::AlreadyPending);budget.finish("workspace");
        }
        assert_eq!(budget.schedule("workspace",now),Decision::Exhausted);
        assert!(matches!(budget.schedule("main",now),Decision::Retry{..}));
        budget.manual_reset("workspace");assert!(matches!(budget.schedule("workspace",now),Decision::Retry{attempt:1,..}));
    }
    #[test]
    fn old_attempts_expire_and_backoff_is_bounded(){
        let mut budget=Budget::default();let now=Instant::now();
        assert_eq!(budget.schedule("main",now),Decision::Retry{attempt:1,delay:Duration::from_millis(200)});budget.finish("main");
        assert_eq!(budget.schedule("main",now+Duration::from_secs(61)),Decision::Retry{attempt:1,delay:Duration::from_millis(200)});
    }
}
