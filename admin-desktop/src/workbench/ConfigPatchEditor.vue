<script setup lang="ts">
import {ref,watch} from 'vue';
import {desk,request,apiPath,operation,safely,loadProject,notify} from './store';
import type {Data} from './model';
// Opened from the files page; it starts in the outer directory when the owner was browsing it.
const scope=ref(desk.dialogData?.scope==='pack'&&desk.project?.packRoot?'pack':'game'),format=ref('minecraft-jvm'),target=ref(''),fields=ref(''),plan=ref<Data|null>(null);
let edit=0;
watch(()=>[scope.value,format.value,target.value,fields.value,desk.projectId],()=>{edit++;plan.value=null;});
watch(format,value=>{fields.value=value==='minecraft-jvm'?'-javaagent:.dreamingfish-bootstrap/bootstrap-agent.jar':value==='ini'?'{"UiCustomType":"2"}':'{"theme":"blue"}';},{immediate:true});
function patch(){return format.value==='minecraft-jvm'?{schemaVersion:1,format:format.value,addJvmArguments:fields.value.split(/\r?\n/).map(s=>s.trim()).filter(Boolean)}:{schemaVersion:1,format:format.value,set:JSON.parse(fields.value)};}
async function check(){const visit=edit;const body={id:crypto.randomUUID(),scope:scope.value,target:target.value,patch:JSON.stringify(patch())};await operation(async()=>{const candidate=await request(apiPath('/config-patch'),'POST',body);if(visit===edit)plan.value=candidate;},'正在检查配置合并规则…');}
async function save(){const candidate=plan.value;if(!candidate)return;await operation(async()=>{await request(apiPath('/files/commit'),'POST',{id:candidate.id,action:candidate.existing?'OVERWRITE':'ADD',stamp:candidate.stamp});plan.value=null;await loadProject();desk.dialog='';notify('合并规则已保存，检查改动并发布后，玩家才会收到');});}
</script>
<template>
 <div class="config-patch-editor">
  <p class="field-help">只改玩家现有配置文件里的某几项，保留玩家自己改过的其他设置。普通模组、图片或教程直接导入即可；只想第一次提供、之后交给玩家的配置，用“首次提供”维护方式更简单。</p>
  <div class="form-grid"><label>目标目录<select v-model="scope"><option value="game">游戏实例目录</option><option value="pack" :disabled="!desk.project?.packRoot">整合包外层目录</option></select></label><label>配置格式<select v-model="format"><option value="minecraft-jvm">Minecraft 版本 JSON 的 JVM 参数</option><option value="json">JSON 字段合并</option><option value="ini">INI 键值合并</option></select></label></div>
  <p v-if="format==='minecraft-jvm'" class="field-help">这个选项修改游戏版本目录里的“版本名.json”。如果要改 PCL“版本设置”里的 JVM 参数，请选择“INI 键值合并”，文件路径填 PCL/Setup.ini。</p>
  <p v-else-if="format==='ini'" class="field-help">用于 PCL/Setup.ini 等 INI 文件。下方填写需要改动的键名和值；PCL 版本独立 JVM 参数的键名是 VersionAdvanceJvm，它的值会整串替换，不会自动保留原有参数。</p>
  <p v-else class="field-help">用于 JSON 配置文件，只改你填写的字段。不要把整个配置文件复制到下面的输入框。</p>
  <label>实际配置文件的相对路径<input v-model="target" placeholder="例如：版本名.json 或 PCL/Setup.ini"></label>
  <label>{{format==='minecraft-jvm'?'新增参数，每行一项':'要修改的字段和值，以 JSON 对象填写'}}<textarea v-model="fields" rows="5" spellcheck="false"/></label>
  <p v-if="format==='minecraft-jvm'" class="field-help">保留其他 JVM 参数和条件参数。同一份 Bootstrap Agent 参数会替换为你填写的一项，避免重复。参数供下一次启动使用，不能改变已经运行的 Java 进程。</p>
  <p v-else class="field-help">支持 UTF-8 和带 BOM 的 UTF-16LE。JSON 递归合并对象，数组按填写值替换；INI 编辑默认无节的键，可导入带 section 字段的规则指定节。未知编码或字段类型冲突不会强行覆盖。</p>
  <div class="form-actions"><button :disabled="desk.busy||!target||!fields" @click="safely(check)">检查合并规则</button><template v-if="plan"><span class="field-help">{{plan.existing?'将替换已有合并规则，标准目录旧副本可从导入记录恢复':'将新增合并规则'}}。玩家配置在发布后按指定字段合并。</span><button class="accent" :disabled="desk.busy" @click="safely(save)">保存规则</button></template></div>
  <p v-if="!target" class="field-help">先填写要修改的配置文件路径，才能检查规则。检查和保存后，还需要发布版本，玩家才会收到。</p>
  <p class="field-help">账户、凭据等字段不能通过这里改写。更新前会备份原文件，无法识别的格式会保留原文件并提示。</p>
  <p class="field-help">保存后，规则文件（实际文件名后加 .dfs-patch.json）会出现在文件列表里。删除规则时请选择“停止维护，留给玩家”，不会把玩家的配置文件一起删除。不同启动器读取和写回设置的时间不同，效果需在退出并重新打开启动器后核对。</p>
 </div>
</template>
