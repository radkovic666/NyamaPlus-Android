#!/usr/bin/env python3
"""Run production caption scripts in Node: real JS, synthetic DOM, no provider/hardware claims."""
import json
import pathlib
import re
import subprocess

root = pathlib.Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/java/fun/nyama/tv/TvSubtitleScript.java').read_text()
script = re.search(r'String code = """\n(.*?)""";', source, re.S).group(1).replace('\\\\', '\\')
policy_source = (root / 'app/src/main/java/fun/nyama/tv/TvSubtitlePolicy.java').read_text()
policy = re.search(r'String policy = """\n(.*?)""";', policy_source, re.S).group(1).replace('\\\\', '\\')
tests = r'''
const assert=require('node:assert/strict');
const vm=require('node:vm');
const origin='https://vaplayer.ru',path='/embed/tv/tt0944947/2/3';
let checks=0;
class Cue {
  constructor(start,end,text){this.startTime=start;this.endTime=end;this.text=text;}
}
function track(label,language='bg',kind='subtitles',cues=[new Cue(10,12,'Text')]){
  return {label,language,kind,mode:'disabled',cues,addCue(cue){this.cues.push(cue);}};
}
function fixture(tracks,options={}){
  const nodes=tracks.map((t,i)=>({track:t,readyState:2,src:'https://example.com/sub-'+i+'.vtt?private=not-persisted'}));
  const doc={defaultView:{VTTCue:Cue},querySelectorAll(selector){
    if(selector==='iframe')return options.frames||[];
    if(selector==='video')return options.videos||[video];
    return options.buttons||[];
  }};
  const video={textTracks:tracks,ownerDocument:doc,currentSrc:'https://example.com/media',readyState:1,duration:3600,
    getBoundingClientRect(){return {width:1000,height:600};},
    querySelectorAll(){return nodes;},
    addTextTrack(kind,label,language){const t=track(label,language,kind,[]);tracks.push(t);return t;},
    play(){throw Error('Must not start playback');},pause(){throw Error('Must not pause playback');}};
  Object.defineProperty(video,'currentTime',{get(){return 25;},set(){throw Error('Must not seek video');}});
  const context={document:doc,location:{origin,pathname:path},WeakMap,Date,Math,JSON};context.window=context;
  vm.createContext(context);
  function run(command='inspect',key='',offset=0,imported=null,token){
    const s=script.replaceAll('__ORIGIN__',JSON.stringify(origin)).replaceAll('__PATH__',JSON.stringify(path))
      .replaceAll('__COMMAND__',JSON.stringify(command)).replaceAll('__TOKEN__',JSON.stringify(token===undefined?(context.__nyamaTvCaptions?.token||''):token))
      .replaceAll('__KEY__',JSON.stringify(key)).replaceAll('__OFFSET__',String(offset)).replaceAll('__IMPORT__',JSON.stringify(imported));
    return JSON.parse(vm.runInContext(s,context));
  }
  return {run,video,nodes,context,doc};
}
function check(name,body){body();checks++;console.log('PASS '+name);}
check('enumerate real caption tracks and labels only',()=>{
  const f=fixture([track('BG release A'),track('BG release B'),track('Chapters','en','metadata')]);
  const s=f.run();assert.equal(s.ok,true);assert.deepEqual(s.tracks.map(t=>t.label),['BG release A','BG release B']);
});
check('select actual alternative; never invent a release',()=>{
  const f=fixture([track('BG A'),track('BG B'),track('English','en')]);const s=f.run();
  const selected=f.run('select',s.tracks[1].key);assert.equal(selected.tracks[1].selected,true);assert.equal(f.video.__nyamaSubtitleManual,true);
  assert.equal(f.run('select','unavailable').ok,false);
});
check('off leaves metadata alone',()=>{
  const a=track('BG'),meta=track('Metadata','en','metadata');a.mode='showing';meta.mode='hidden';
  const f=fixture([a,meta]);f.run();assert.equal(f.run('off').ok,true);assert.equal(a.mode,'disabled');assert.equal(meta.mode,'hidden');
});
check('timing is absolute, resettable, and independent of video seeking',()=>{
  const t=track('BG');t.mode='showing';const f=fixture([t]);const s=f.run(),key=s.tracks[0].key;
  f.run('offset',key,.5);assert.equal(t.cues[0].startTime,10.5);
  f.run('offset',key,1);assert.equal(t.cues[0].startTime,11);
  f.run('offset',key,-.5);assert.equal(t.cues[0].startTime,9.5);
  f.run('offset',key,0);assert.equal(t.cues[0].startTime,10);assert.equal(f.video.currentTime,25);
});
check('offset limits and step validation preserve existing cues',()=>{
  const t=track('BG');t.mode='showing';const f=fixture([t]),s=f.run(),key=s.tracks[0].key;
  for(const offset of [5.5,-5.5,.1,NaN,Infinity])assert.equal(f.run('offset',key,offset).ok,false);
  assert.equal(t.cues[0].startTime,10);
});
check('negative delay handles cues before zero without displaying expired text',()=>{
  const t=track('BG','bg','subtitles',[new Cue(0,1,'Before zero'),new Cue(3,6,'Visible')]);t.mode='showing';
  const f=fixture([t]),s=f.run();assert.equal(f.run('offset',s.tracks[0].key,-2).ok,true);
  assert.equal(t.cues[0].text,'');assert.equal(t.cues[1].startTime,1);
  f.run('offset',s.tracks[0].key,0);assert.equal(t.cues[0].text,'Before zero');assert.equal(t.cues[0].startTime,0);
});
check('fingerprint uses original cues despite offset changes',()=>{
  const t=track('BG');t.mode='showing';const f=fixture([t]),s=f.run();const changed=f.run('offset',s.tracks[0].key,2);
  assert.equal(changed.signature,s.signature);assert.equal(changed.tracks[0].offset,2);
});
check('in-band or incomplete tracks never advertise delay',()=>{
  const t=track('BG');t.mode='showing';const f=fixture([t]);f.nodes[0].readyState=1;
  const s=f.run();assert.equal(s.tracks[0].canOffset,false);assert.equal(f.run('offset',s.tracks[0].key,1).ok,false);
});
check('read-only timing failure does not disable captions',()=>{
  const cue=new Cue(10,12,'Caption');Object.defineProperty(cue,'endTime',{get(){return 12;},set(){throw Error('read-only');}});
  const t=track('BG','bg','subtitles',[cue]);t.mode='showing';const f=fixture([t]),s=f.run();
  assert.equal(f.run('offset',s.tracks[0].key,1).ok,false);assert.equal(t.mode,'showing');assert.equal(cue.startTime,10);assert.equal(cue.text,'Caption');
});
check('stale session and removed tracks cannot select or change cues',()=>{
  const t=track('BG');const f=fixture([t]),s=f.run();assert.equal(f.run('select',s.tracks[0].key,0,null,'stale').ok,false);
  f.video.textTracks.splice(0);assert.equal(f.run('select',s.tracks[0].key).ok,false);
});
check('source changes reset session identity',()=>{
  const f=fixture([track('BG')]),s=f.run();f.video.currentSrc='https://example.com/new-media';const next=f.run();assert.notEqual(next.token,s.token);
  assert.equal(f.run('off','',0,null,s.token).ok,false);
});
check('changed cue files clear session offset',()=>{
  const t=track('BG');t.mode='showing';const f=fixture([t]),s=f.run();f.run('offset',s.tracks[0].key,1);
  t.cues=[new Cue(10,12,'Different release')];const next=f.run();assert.equal(next.tracks[0].offset,0);assert.notEqual(next.signature,s.signature);
});
check('protected frame cannot be enumerated',()=>{
  let focused=false;const frame={get contentDocument(){throw Error('cross-origin');},focus(){focused=true;},getBoundingClientRect(){return {width:1000,height:600};}};
  const f=fixture([],{videos:[],frames:[frame]});assert.equal(f.run().ok,false);assert.equal(f.run('provider').ok,true);assert.equal(focused,true);
});
check('same-origin nested HTML5 tracks remain usable',()=>{
  const nested=fixture([track('Inside same-origin frame')]);
  const frame={contentDocument:nested.doc};const outer=fixture([],{videos:[],frames:[frame]});assert.equal(outer.run().tracks[0].label,'Inside same-origin frame');
});
check('ambiguous multiple visible videos fail closed',()=>{
  const f=fixture([track('BG')]);f.doc.querySelectorAll=(selector)=>selector==='video'?[f.video,f.video]:[];assert.equal(f.run().ok,false);
});
check('document origin and exact episode path are verified',()=>{
  const f=fixture([track('BG')]);f.context.location.pathname='/embed/tv/tt0944947/2/4';assert.equal(f.run().ok,false);
  f.context.location.pathname=path;f.context.location.origin='https://unrelated.example';assert.equal(f.run().ok,false);
});
check('local import uses browser cues; no external downloads or duplicate tracks',()=>{
  const f=fixture([track('Provider')]);f.run();const file={id:'a'.repeat(64),label:'Authorized local file',language:'bg',cues:[[1,2,'Hello'],[3,4,'World']]};
  const loaded=f.run('import','',0,file);assert.equal(loaded.tracks.length,2);assert.equal(loaded.tracks[1].selected,true);
  const local=f.video.textTracks[1];assert.equal(local.cues[0].text,'Hello');
  f.run('import','',0,file);assert.equal(f.video.textTracks.length,2);assert.equal(f.run('offset',loaded.tracks[1].key,.5).ok,true);
});
check('imported text cannot become markup or execute script',()=>{
  const f=fixture([]);f.run();const payload='<script>globalThis.pwned=true</script> & <b>text</b>';
  const s=f.run('import','',0,{id:'b'.repeat(64),label:'Text',language:'en',cues:[[1,2,payload]]});
  assert.equal(s.ok,true);assert.match(f.video.textTracks[0].cues[0].text,/&lt;script&gt;/);assert.equal(f.context.pwned,undefined);
});
check('bad import is rejected before adding a track',()=>{
  const f=fixture([]);f.run();assert.equal(f.run('import','',0,{id:'c'.repeat(64),cues:[[3,2,'Bad']]}).ok,false);assert.equal(f.video.textTracks.length,0);
});
check('automatic explicitly relinquishes the manual override',()=>{
  const f=fixture([track('BG')]),s=f.run();f.run('select',s.tracks[0].key);assert.equal(f.video.__nyamaSubtitleManual,true);
  f.run('auto');assert.equal(f.video.__nyamaSubtitleManual,undefined);
});
check('automatic policy cannot override manual off or track selection',()=>{
  const p=policy.replaceAll('__ID__',JSON.stringify('tt0944947')).replaceAll('__SEASON__','2').replaceAll('__EPISODE__','3').replaceAll('__LANG__',JSON.stringify('bg|bul'));
  const f=fixture([track('BG'),track('English','en')]);const s=f.run();f.video.addEventListener=()=>{};f.video.textTracks.addEventListener=()=>{};
  f.run('off');f.context.best=f.video;vm.runInContext('(function(){'+p+'})()',f.context);assert.equal(f.video.textTracks[0].mode,'disabled');
  f.run('select',s.tracks[1].key);vm.runInContext('(function(){'+p+'})()',f.context);assert.equal(f.video.textTracks[1].mode,'showing');
});
check('dual-caption playback does not apply delay to a guessed track',()=>{
  const a=track('BG'),b=track('English','en');a.mode='showing';b.mode='showing';const f=fixture([a,b]),s=f.run();
  assert.ok(s.tracks.every(t=>!t.canOffset));assert.equal(s.signature,'');assert.equal(f.run('offset',s.tracks[0].key,1).ok,false);
});
check('failed negative offset restores text when cue timing is read-only',()=>{
  const cue=new Cue(0,1,'Keep caption');Object.defineProperty(cue,'endTime',{get(){return 1;},set(){throw Error('read-only');}});
  const t=track('BG','bg','subtitles',[cue]);t.mode='showing';const f=fixture([t]),s=f.run();
  assert.equal(f.run('offset',s.tracks[0].key,-2).ok,false);assert.equal(cue.text,'Keep caption');
});
check('source switches retain original cue baselines rather than accumulating delay',()=>{
  const t=track('BG');t.mode='showing';const f=fixture([t]),s=f.run();f.run('offset',s.tracks[0].key,1);
  f.video.currentSrc='https://example.com/quality-switch';const next=f.run();assert.notEqual(next.token,s.token);
  f.run('offset',next.tracks[0].key,2);assert.equal(t.cues[0].startTime,12);
});
check('off remains active when a provider loads new caption tracks',()=>{
  const events={};const tracks=[track('BG')];tracks.addEventListener=(name,fn)=>events[name]=fn;
  const button={getAttribute(){return 'Subtitles';},textContent:'',getBoundingClientRect(){return {width:40,height:40};},focus(){},click(){}};
  const f=fixture(tracks,{buttons:[button]});f.run();f.run('off');const added=track('English','en');added.mode='showing';tracks.push(added);events.addtrack();assert.equal(added.mode,'disabled');
  tracks[0].mode='showing';events.change();assert.equal(tracks[0].mode,'disabled');
  f.run('provider');tracks[0].mode='showing';events.change();assert.equal(tracks[0].mode,'showing');
});
console.log(checks+' production TV caption-control scenarios passed (synthetic DOM only).');
'''
subprocess.run(['node', '-e', 'const script=' + json.dumps(script) + ';const policy=' + json.dumps(policy) + ';\n' + tests], check=True)
