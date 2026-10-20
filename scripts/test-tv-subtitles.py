#!/usr/bin/env python3
"""Execute the production Java text-block policy in Node with synthetic HTML5 track metadata."""
import json
import pathlib
import re
import subprocess
source = (pathlib.Path(__file__).resolve().parents[1] / 'app/src/main/java/fun/nyama/tv/TvSubtitlePolicy.java').read_text()
policy = re.search(r'String policy = """\n(.*?)""";', source, re.S).group(1).replace('\\\\', '\\')
policy = policy.replace('__ID__', '"tt0944947"').replace('__SEASON__', '2').replace('__EPISODE__', '3').replace('__LANG__', '"bg|bul"')
tests = r'''
const assert=require('node:assert/strict');
const apply=new Function('best',policy);
function track(label,language='bg',attrs={}) { return {label,language,kind:'subtitles',mode:'disabled',attrs}; }
function video(tracks,readyState=1) {
 const events={};tracks.addEventListener=(name,fn)=>events[name]=fn;
 return {textTracks:tracks,readyState,events,querySelectorAll:()=>tracks.map(t=>({track:t,getAttribute:k=>t.attrs[k]??null})),addEventListener:(n,fn)=>events[n]=fn};
}
let v=video([track('S02E04'),track('tt0944947 S02E03')]);apply(v);
assert.equal(v.textTracks[0].mode,'disabled');assert.equal(v.textTracks[1].mode,'showing');
v=video([track('S02E04')]);apply(v);assert.equal(v.textTracks[0].mode,'disabled');
v=video([track('tt1234567 S02E03')]);apply(v);assert.equal(v.textTracks[0].mode,'disabled');
v=video([track('BG A'),track('BG B')]);apply(v);assert.ok(v.textTracks.every(t=>t.mode==='disabled'));
v=video([track('Bulgarian')]);apply(v);assert.equal(v.textTracks[0].mode,'showing');
v=video([track('English','en'),track('BG')]);apply(v);assert.equal(v.textTracks[0].mode,'disabled');assert.equal(v.textTracks[1].mode,'showing');
v=video([track('Wrong','bg',{'data-season':'2','data-episode':'4'})]);apply(v);assert.equal(v.textTracks[0].mode,'disabled');
v=video([track('remote','bg',{src:'https://example.invalid/sub?season=2&episode=4'})]);apply(v);assert.equal(v.textTracks[0].mode,'disabled');
v=video([track('manual','en'),track('BG')]);v.textTracks[0].mode='showing';apply(v);assert.equal(v.textTracks[0].mode,'showing');assert.equal(v.textTracks[1].mode,'disabled');
v=video([],0);apply(v);v.textTracks.push(track('S02E03'));v.readyState=1;v.events.loadedmetadata();assert.equal(v.textTracks[0].mode,'showing');
v=video([track('BG')]);apply(v);v.textTracks.push(track('tt0944947 S02E03'));v.events.addtrack();assert.equal(v.textTracks[0].mode,'disabled');assert.equal(v.textTracks[1].mode,'showing');
v.textTracks[1].mode='disabled';v.events.addtrack();assert.equal(v.textTracks[1].mode,'disabled');
console.log('12 production subtitle-policy scenarios passed (synthetic DOM; no provider/hardware claims).');
'''
subprocess.run(['node', '-e', 'const policy=' + json.dumps(policy) + ';\n' + tests], check=True)
