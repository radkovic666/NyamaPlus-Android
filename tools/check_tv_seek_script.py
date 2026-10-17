"""Execute the actual Java-built TV seek scripts in V8 against controlled HTML5 media signals."""
from pathlib import Path
import subprocess
import tempfile
import os

# Run: python3 tools/check_tv_seek_script.py (requires a JDK and Node on PATH).
repository = Path(__file__).resolve().parents[1]
source = (repository / 'app/src/main/java/fun/nyama/tv/MoviePlayerActivity.java').read_text()

def method(signature):
    start = source.index(signature)
    opening = source.index('{', start)
    depth, quote, escaped = 0, None, False
    for index in range(opening, len(source)):
        char = source[index]
        if quote:
            if escaped:
                escaped = False
            elif char == '\\':
                escaped = True
            elif char == quote:
                quote = None
        elif char in ('"', "'"):
            quote = char
        elif char == '{':
            depth += 1
        elif char == '}':
            depth -= 1
            if not depth:
                return source[start:index + 1].replace('private String', 'static String', 1)
    raise AssertionError('Method extraction failed')

with tempfile.TemporaryDirectory(prefix='nyama-seek-') as directory:
    root = Path(directory)
    java = 'import java.nio.file.*; public class SeekScripts {\n'
    java += method('private String accessibleVideoScript()') + '\n'
    java += method('private String tvSeekScript(') + '\n'
    java += '''public static void main(String[] args) throws Exception {
      Files.writeString(Path.of(args[0], "direct10.js"), tvSeekScript(1,10,false));
      Files.writeString(Path.of(args[0], "direct90.js"), tvSeekScript(2,90,false));
      Files.writeString(Path.of(args[0], "left10.js"), tvSeekScript(3,-10,false));
      for(int n=1;n<=3;n++) Files.writeString(Path.of(args[0], "poll"+n+".js"), tvSeekScript(n,10,true));
    }}'''
    (root / 'SeekScripts.java').write_text(java)
    subprocess.run([str(Path(os.environ['JAVA_HOME']) / 'bin/javac') if os.environ.get('JAVA_HOME') else 'javac', str(root / 'SeekScripts.java')], check=True)
    subprocess.run([str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java', '-cp', directory, 'SeekScripts', directory], check=True)
    (root / 'verify.js').write_text(r'''
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const root=process.argv[2]; let count=0;
function player(options={}) {
  const p={position:options.position??30,duration:options.duration??600,assignments:[],attached:true};
  const video={duration:p.duration,paused:!!options.paused,readyState:options.readyState??4,seeking:!!options.seeking,
    seekable:{length:options.noRanges?0:1,start:()=>0,end:()=>p.duration},
    getBoundingClientRect:()=>({width:options.hidden?0:1920,height:options.hidden?0:1080}),
    play:()=>assert.fail('Seeking must never change the pause state'),pause:()=>assert.fail('Unexpected pause')};
  Object.defineProperty(video,'currentTime',{get:()=>p.position,set:value=>{
    p.assignments.push(value); if(options.ignoreAssignment)return;
    p.position=value; video.seeking=true;
  }});
  const frame={};Object.defineProperty(frame,'contentDocument',{get:()=>{throw Error('Cross-origin');}});
  const doc={querySelectorAll:type=>type==='video'?(options.crossOrigin?[]:[video]):options.crossOrigin?[frame]:[],
    documentElement:{contains:value=>p.attached&&value===video}};
  video.ownerDocument=doc;
  const context=vm.createContext({document:doc});context.window=context;
  p.video=video;p.run=name=>JSON.parse(vm.runInContext(fs.readFileSync(root+'/'+name+'.js','utf8'),context));
  p.settle=()=>{video.seeking=false;};return p;
}
function test(name,run){run();count++;}
test('initial seek is applied once while repeated probes wait',()=>{
  const p=player();assert.equal(p.run('direct10').state,'waiting');
  for(let n=0;n<5;n++)assert.equal(p.run('poll1').state,'waiting');
  assert.deepEqual(p.assignments,[40]);p.settle();assert.equal(p.run('poll1').state,'settled');
});
test('ten intentional presses add100seconds through10then90 coalescing',()=>{
  const p=player();p.run('direct10');p.settle();p.run('poll1');
  p.run('direct90');assert.deepEqual(p.assignments,[40,130]);p.settle();assert.equal(p.run('poll2').position,130);
});
test('forward near end clamps to measured duration',()=>{
  const p=player({position:595});p.run('direct10');assert.equal(p.position,600);p.settle();assert.equal(p.run('poll1').state,'settled');
});
test('backward near start clamps to zero',()=>{
  const p=player({position:3});p.run('left10');assert.equal(p.position,0);p.settle();assert.equal(p.run('poll3').state,'settled');
});
test('paused media stays paused while seeking',()=>{
  const p=player({paused:true});p.run('direct10');p.settle();assert.equal(p.run('poll1').paused,true);
});
test('playing media stays playing while seeking',()=>{
  const p=player();p.run('direct10');p.settle();assert.equal(p.run('poll1').paused,false);
});
test('existing provider seek settles before next assignment',()=>{
  const p=player({seeking:true});assert.equal(p.run('direct10').state,'waiting');assert.equal(p.assignments.length,0);
  p.position=200;p.settle();assert.equal(p.run('poll1').state,'waiting');assert.deepEqual(p.assignments,[210]);
  p.settle();assert.equal(p.run('poll1').state,'settled');
});
test('setter which silently ignores new position cannot claim success',()=>{
  const p=player({ignoreAssignment:true});assert.equal(p.run('direct10').state,'failed');assert.equal(p.position,30);
});
test('unready media cannot receive a speculative assignment',()=>{
  const p=player({readyState:0});assert.equal(p.run('direct10').state,'unready');assert.equal(p.assignments.length,0);
});
test('missing seekable ranges cannot receive an assignment',()=>{
  const p=player({noRanges:true});assert.equal(p.run('direct10').state,'unready');assert.equal(p.assignments.length,0);
});
test('cross-origin frame remains inaccessible without DOM bypass',()=>{
  const p=player({crossOrigin:true});assert.equal(p.run('direct10').state,'inaccessible');assert.equal(p.assignments.length,0);
});
test('hidden unrelated media is not selected',()=>{
  const p=player({hidden:true});assert.equal(p.run('direct10').state,'inaccessible');assert.equal(p.assignments.length,0);
});
test('detached media invalidates completion without new assignment',()=>{
  const p=player();p.run('direct10');p.attached=false;p.settle();assert.equal(p.run('poll1').state,'failed');assert.equal(p.assignments.length,1);
});
test('wrong operation cannot acknowledge another seek',()=>{
  const p=player();p.run('direct10');p.settle();assert.equal(p.run('poll2').state,'failed');assert.equal(p.assignments.length,1);
});
console.log(count+' actual generated JavaScript media scenarios passed (mock DOM/media; no live provider).');
''')
    subprocess.run(['node', str(root / 'verify.js'), directory], check=True)
