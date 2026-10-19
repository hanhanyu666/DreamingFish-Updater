// Small native probe of the production migration modules, with caller-owned fixtures only.
#[path = "../../admin-desktop/src-tauri/src/profiles.rs"] mod profiles;
#[path = "../../admin-desktop/src-tauri/src/migrate.rs"] mod migrate;
use std::path::PathBuf;
fn main() {
    let args:Vec<String>=std::env::args().collect();assert!(args.len()==3||args.len()==4);
    let old=PathBuf::from(&args[1]);let new=PathBuf::from(&args[2]);
    assert!(old.is_absolute()&&new.is_absolute());
    assert_ne!(old.components().next(),new.components().next(),"this probe requires separate volumes");
    assert!(old.parent().unwrap().file_name().unwrap().to_string_lossy().starts_with("dfs-cross-volume-"));
    assert!(new.parent().unwrap().file_name().unwrap().to_string_lossy().starts_with("cross-volume-"));
    assert!(old.parent().unwrap().join(".owned-synthetic-fixture").is_file());
    if new.exists(){assert!(std::fs::read_dir(&new).unwrap().all(|entry|entry.unwrap().file_name()=="migration.lock"));}
    let data=management_data(&old.join("management"));
    let before=std::fs::read(data.join("management.db")).unwrap();
    let key=std::fs::read(data.join("keys/test.pk8")).unwrap();
    let result=migrate::legacy_into(&old,&new);
    if args.get(3).map(String::as_str)==Some("blocked") {
        assert_eq!(result.root,old,"{result:?}");assert_eq!(result.status,migrate::MigrationStatus::Fallback);
        assert_eq!(std::fs::read(data.join("management.db")).unwrap(),before);
        println!("{}",serde_json::json!({"passed":true,"status":"blocked-by-active-java-core","source":old}));return;
    }
    assert_eq!(result.root,new,"{result:?}");assert_eq!(result.status,migrate::MigrationStatus::Complete,"{result:?}");
    let copied=management_data(&new.join("management"));
    assert_eq!(std::fs::read(copied.join("management.db")).unwrap(),before);
    assert_eq!(std::fs::read(copied.join("keys/test.pk8")).unwrap(),key);
    let config=profiles::Config::load(&new).unwrap();assert_eq!(config.profiles.len(),1);
    assert!(matches!(&config.profiles[0].connection,profiles::Connection::Local{home} if PathBuf::from(home)==new.join("management")));
    println!("{}",serde_json::json!({"passed":true,"source":old,"target":new,"status":"complete","connections":config.profiles.len(),"databaseBytes":before.len(),"keyBytes":key.len()}));
}
fn management_data(home:&std::path::Path)->PathBuf {
    let value:serde_json::Value=serde_json::from_slice(&std::fs::read(home.join("management-settings.json")).unwrap()).unwrap();
    let path=PathBuf::from(value["dataDirectory"].as_str().unwrap());if path.is_absolute(){path}else{home.join(path)}
}
