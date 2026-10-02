// Explicit opt-in evaluation. Reads a local credential file; never prints or saves its key.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const ts = require(process.env.HARMONY_TYPESCRIPT || 'D:/devco/DevEco Studio/tools/hvigor/hvigor/node_modules/typescript/lib/typescript.js');
require.extensions['.ts']=(module,filename)=>module._compile(ts.transpileModule(fs.readFileSync(filename,'utf8'),{
  compilerOptions:{target:ts.ScriptTarget.ES2020,module:ts.ModuleKind.CommonJS}
}).outputText,filename);
const root=path.resolve(__dirname,'../../entry/src/main/ets');
const api=require(path.join(root,'model/CourseAssistant.ts'));
const core=require(path.join(root,'model/ScheduleCore.ts'));
const raw=fs.readFileSync(process.argv[2],'utf8');const key=raw.match(/sk-[a-zA-Z0-9_-]+/)?.[0];if(!key)throw new Error('本机配置未包含有效 Key');
const config={...api.defaultAssistantConfig(),apiKey:key};
const now=new Date(2026,9,2,12);
const row={id:1,courseName:'神经网络与深度学习导论',teacher:'郑津',classroom:'明理楼B407',dayOfWeek:2,
  startSection:3,endSection:5,startWeek:1,endWeek:7,weekType:0,semesterId:1,colorIndex:4,note:'原备注保留',reminderMinutes:-1,createTime:123};
const initial={...core.defaultState(new Date(2026,8,28).getTime()),courses:[row,
  {...row,id:2,courseName:'高等数学',dayOfWeek:1,startSection:1,endSection:2,endWeek:16},
  {...row,id:3,courseName:'大学英语',dayOfWeek:3,startSection:1,endSection:2,endWeek:16}]};
const noChange=plan=>assert.equal(plan.courses.length+plan.updates.length+plan.deletions.length+Number(plan.undo),0);
const changed=(plan,patch)=>{assert.equal(plan.updates.length,1);assert.equal(plan.updates[0].original.id,1);
  assert.deepEqual(plan.updates[0].replacements,[{...row,...patch}]);assert.equal(plan.courses.length+plan.deletions.length,0);};
const cases=[
  ['只改教室','把神经网络与深度学习导论的教室改为B201，其他都不变',p=>changed(p,{classroom:'B201'})],
  ['保留课时','把周二那门神经网络课移到周四第5节，周次和其他信息不变',p=>changed(p,{dayOfWeek:4,startSection:5,endSection:7})],
  ['补充备注','给神经网络与深度学习导论补充备注：带教材。保留原备注',p=>changed(p,{note:row.note+'\n带教材'})],
  ['课前提醒','神经网络与深度学习导论改为提前10分钟提醒',p=>changed(p,{reminderMinutes:10})],
  ['取消单次课','取消下周周二那次神经网络课，保留其他周',p=>{
    const change=api.applyAssistantPlan(initial,p);assert.equal(p.deletions.length,0);
    assert.deepEqual(change.state.courses.filter(c=>c.courseName===row.courseName).flatMap(api.assistantWeeks).sort((a,b)=>a-b),[1,3,4,5,6,7]);}],
  ['移动单次课','仅把下周周二那次神经网络课移到同周周四第5节，其他周不动',p=>{
    const courses=api.applyAssistantPlan(initial,p).state.courses.filter(c=>c.courseName===row.courseName);
    const moved=courses.filter(c=>c.dayOfWeek===4);assert.equal(moved.length,1);assert.deepEqual(api.assistantWeeks(moved[0]),[2]);assert.equal(moved[0].endSection,7);
    assert.deepEqual(courses.filter(c=>c.dayOfWeek===2).flatMap(api.assistantWeeks).sort((a,b)=>a-b),[1,3,4,5,6,7]);}],
  ['删除整条安排','删除本学期整条神经网络与深度学习导论安排',p=>{assert.equal(p.deletions.length,1);assert.equal(p.deletions[0].id,1);assert.equal(p.updates.length+p.courses.length,0);}],
  ['查询本地筛选','查询这周郑津老师周二的课',p=>{noChange(p);assert.ok(p.query);assert.equal(api.assistantQuery(initial,p.query,1,now)[0].id,1);}],
  ['缺少课名追问','明天第4节帮我加一门课',p=>{noChange(p);assert.equal(p.query,null);assert.equal(p.queryIds.length,0);}],
  ['普通聊天不改课','谢谢你，今天很开心',p=>{noChange(p);assert.equal(p.query,null);assert.equal(p.queryIds.length,0);}],
  ['不可撤销不模拟','撤销上次操作',p=>{noChange(p);assert.equal(p.query,null);}],
  ['修正保留未执行改动','教室再改成B201，其他保持方案中的安排',p=>changed(p,{dayOfWeek:4,startSection:5,endSection:7,classroom:'B201'}),true],
];
async function run(){
  const results=[];
  for(const [name,text,check,refine]of cases.filter(item=>!process.argv[3]||item[0]===process.argv[3])){
    const chat=api.newAssistantChat(initial,{chats:[]});
    if(refine){chat.pending=api.parseAssistantResponse(JSON.stringify({choices:[{finish_reason:'stop',message:{content:JSON.stringify({version:1,action:'change',reply:'待确认',updates:[{id:1,dayOfWeek:4,startSection:5}]})}}]}),initial,1,undefined,now);
      api.appendAssistantMessage(chat,'user','把神经网络移到周四第5节');api.appendAssistantMessage(chat,'assistant','已整理方案，尚未执行','confirmation');}
    api.appendAssistantMessage(chat,'user',text);
    const request=api.createAssistantRequest(config,initial,chat,1,false,now);const start=Date.now();let status='PASS',reason='';
    try{
      const response=await fetch(api.assistantEndpoint(config),{method:'POST',headers:{Authorization:'Bearer '+key,'Content-Type':'application/json'},body:request.body,signal:AbortSignal.timeout(65000)});
      if(!response.ok)throw new Error('HTTP '+response.status);
      const body=await response.text();
      let plan;
      try{plan=api.parseAssistantResponse(body,initial,1,request.candidateIds,now);check(plan);}
      catch(error){if(!body.includes(key))fs.writeFileSync(path.join(__dirname,'evaluation-failure.json'),body);throw error;}
    }catch(error){status='FAIL';reason=error.name==='AssertionError'?'语义断言不通过':(error.message.startsWith('HTTP ')?error.message:error.message.replaceAll(key,'[redacted]').slice(0,160));}
    const seconds=((Date.now()-start)/1000).toFixed(1);results.push({name,status,seconds,reason});console.log(status+' '+name+' '+seconds+'s '+reason);
  }
  const report=['# HarmonyOS DeepSeek 实际模型评测','',`模型：${config.model}。使用本机配置，凭证不写入报告。课表为合成样例；日期上下文固定为 2026-10-02。`,'',
    '|场景|结果|用时|','|---|---|---|',...results.map(r=>`|${r.name}|${r.status}${r.reason?'：'+r.reason:''}|${r.seconds}s|`),'',
    `通过 ${results.filter(r=>r.status==='PASS').length}/${results.length}。本结果反映此轮样例，不代表所有自然语言输入均正确。`,''].join('\n');
  fs.writeFileSync(path.join(__dirname,process.argv[3]?'deepseek-recheck.md':'deepseek-evaluation.md'),report);if(results.some(r=>r.status!=='PASS'))process.exitCode=1;
}
run().catch(()=>{console.error('评测执行失败，凭证未输出。');process.exitCode=1;});
