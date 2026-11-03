import test from 'node:test';
import assert from 'node:assert/strict';
import {playerUpdatePresentation,playerDeliveryPresentation,playerUpgradeChoices,officialOffer,compareVersions,autoPublishPresentation,autoUploads,uploadReminder,savedHostingPresentation,downloadProgress} from '../src/workbench/player-update-presentation.ts';

test('only programs newer than the version players already receive are offered',()=>{
 const byUploadTime=[{version:'0.3.2'},{version:'0.3.10'},{version:'0.3.3'}];
 assert.deepEqual(playerUpgradeChoices(byUploadTime,{version:'0.3.3'}).map(p=>p.version),['0.3.10']);
 assert.deepEqual(playerUpgradeChoices(byUploadTime,undefined).map(p=>p.version),['0.3.10','0.3.3','0.3.2']);
 assert.equal(compareVersions('0.3.10','0.3.9'),1);assert.equal(compareVersions('1.0.0-beta','1.0.0'),-1);
});

test('the official release is offered for publishing only when it is newer than the approved version',()=>{
 assert.equal(officialOffer(null,undefined),null);
 assert.equal(officialOffer({version:'0.3.4'},undefined).newer,true);
 assert.equal(officialOffer({version:'0.3.4'},{version:'0.3.3'}).newer,true);
 const current=officialOffer({version:'0.3.3'},{version:'0.3.3'});
 assert.equal(current.newer,false);assert.match(current.label,/已是官方最新/);
});

test('automatic publishing reports its last problem instead of pretending it published',()=>{
 assert.equal(autoPublishPresentation({enabled:false,publishedVersion:'0.3.4'}).title,'');
 assert.equal(autoPublishPresentation({enabled:true,problem:'玩家端升级已暂停，没有自动发布新版'}).tone,'warning');
 assert.match(autoPublishPresentation({enabled:true,publishedVersion:'0.3.4'}).title,/0.3.4/);
 assert.match(autoPublishPresentation({enabled:true}).title,/等待第一次检查/);
});

test('an unconfigured project explains the owner decision instead of implying players are blocked',()=>{
 const view=playerUpdatePresentation({enabled:false,targets:{}},'windows-x64');
 assert.equal(view.badge,'尚未开启');assert.equal(view.hasTargets,false);
 assert.match(view.detail,/检查官方新版.*发布给玩家/);
});

test('pause and a missing platform target cannot be mistaken for a promised upgrade',()=>{
 const targets={'windows-x64':{version:'0.3.3'}};
 const paused=playerUpdatePresentation({enabled:false,targets},'windows-x64');
 assert.equal(paused.badge,'已暂停');assert.match(paused.detail,/不会退回旧版/);
 const active=playerUpdatePresentation({enabled:true,targets},'windows-x64');
 assert.match(active.title,/0.3.3/);
 const linux=playerUpdatePresentation({enabled:true,targets},'linux-x64');
 assert.match(linux.title,/还没有/);assert.equal(linux.target,undefined);
});

test('an old upload receipt or an export alone cannot claim the current choice was uploaded',()=>{
 const policy={revision:3};
 assert.equal(playerDeliveryPresentation(policy,{uploadedRevision:2,uploadedAt:'2026-11-01'}).tone,'info');
 const exported=playerDeliveryPresentation(policy,{exportedRevision:3,exportedAt:'2026-11-01'});
 assert.match(exported.title,/还要上传/);
 assert.notEqual(playerDeliveryPresentation(policy,{exportedRevision:3}).tone,'success');
 assert.notEqual(playerDeliveryPresentation(undefined,{}).tone,'success');
});

test('a matching built-in receipt remains limited to that destination and unreadable records stay unconfirmed',()=>{
 const uploaded=playerDeliveryPresentation({revision:3},{uploadedRevision:3,uploadedAt:'2026-11-01',provider:'s3'});
 assert.equal(uploaded.tone,'success');assert.match(uploaded.detail,/其他下载网站.*仍需分别核对/);
 const bad=playerDeliveryPresentation({revision:3},{uploadedRevision:3,uploadedAt:'2026-11-01',warning:'unreadable'});
 assert.equal(bad.tone,'warning');assert.match(bad.title,/无法确认/);
});

test('saved external hosting changes the upload reminder only while automatic upload can run',()=>{
 const policy={revision:4},saved={autoUpload:true,destination:'mods · oss-cn-hangzhou.aliyuncs.com'};
 assert.equal(autoUploads(saved),true);assert.equal(autoUploads({...saved,autoUpload:false}),false);
 assert.equal(autoUploads({...saved,problem:'已保存的外部托管设置无法读取'}),false);
 assert.match(uploadReminder(saved),/自动上传/);assert.match(uploadReminder(null),/重新导出并上传/);
 assert.match(playerDeliveryPresentation(policy,{},saved).title,/稍后自动上传/);
 assert.equal(playerDeliveryPresentation(policy,{},{...saved,autoStatus:{problem:'上传 S3 对象失败：403'}}).tone,'warning');
 assert.equal(playerDeliveryPresentation(policy,{uploadedRevision:4,uploadedAt:'2026-11-03T00:00:00Z',provider:'s3'},saved).tone,'success');
 assert.match(playerDeliveryPresentation(policy,{},{...saved,autoUpload:false}).title,/还需要上传一次/);
});

test('the saved hosting card reports failures before claiming the destination is current',()=>{
 assert.equal(savedHostingPresentation(null).title,'');
 assert.equal(savedHostingPresentation({problem:'无法读取'}).tone,'warning');
 assert.equal(savedHostingPresentation({autoUpload:true,autoStatus:{problem:'403',uploadedAt:'2026-11-03T00:00:00Z'}}).tone,'warning');
 assert.equal(savedHostingPresentation({autoUpload:true,autoStatus:{uploadedAt:'2026-11-03T00:00:00Z'}}).tone,'success');
 assert.match(savedHostingPresentation({autoUpload:false}).title,/已关闭/);
});

test('the official download progress counts bytes only while the publish task is still running',()=>{
 assert.equal(downloadProgress(undefined),null);
 assert.equal(downloadProgress({state:'QUEUED',phase:'等待服务端确认',completed:0,total:1}),null);
 const running=downloadProgress({state:'RUNNING',phase:'正在下载官方玩家端程序',completed:12_500_000,total:37_124_447});
 assert.equal(running.percent,33);assert.equal(running.phase,'正在下载官方玩家端程序');
 assert.equal(downloadProgress({state:'RUNNING',phase:'x',completed:50,total:40}).percent,100);
 assert.equal(downloadProgress({state:'SUCCEEDED',phase:'已完成',completed:40,total:40}),null);
});
