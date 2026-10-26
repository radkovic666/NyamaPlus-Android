package fun.nyama.tv;

import org.json.JSONObject;

/** Same-origin HTML5 captions only. Never reads protected frame DOM or changes video time. */
final class TvSubtitleScript {
    private TvSubtitleScript() { }

    static String run(String origin, String path, String command, String token, String key,
                      double offset, String importedJson) {
        String code = """
            (function(){try{
              function reply(value){return JSON.stringify(value);}
              if(location.origin!==__ORIGIN__||location.pathname!==__PATH__)return reply({ok:false});
              var documents=[],videos=[];
              function visit(d,depth){
                if(depth>6||documents.length>=16)return;documents.push(d);
                var frames=d.querySelectorAll('iframe');
                for(var i=0;i<frames.length;i++){try{if(frames[i].contentDocument)visit(frames[i].contentDocument,depth+1);}catch(opaque){}}
              }
              visit(document,0);
              if(__COMMAND__==='provider'){
                for(var di=0;di<documents.length;di++){
                  var buttons=documents[di].querySelectorAll('button,[role="button"]');
                  for(var bi=0;bi<Math.min(buttons.length,300);bi++){
                    var b=buttons[bi],r=b.getBoundingClientRect();
                    var name=(b.getAttribute('aria-label')||'')+' '+(b.getAttribute('title')||'')+' '+(b.textContent||'');
                    if(r.width>0&&r.height>0&&/subtitle|caption|субтит|(^|\\s)cc(\\s|$)/i.test(name)){
                      if(window.__nyamaTvCaptions)window.__nyamaTvCaptions.choice='';
                      b.focus();b.click();return reply({ok:true,provider:'control'});
                    }
                  }
                }
                var frames=document.querySelectorAll('iframe'),frame=null,largest=0;
                for(var fi=0;fi<frames.length;fi++){
                  var fr=frames[fi].getBoundingClientRect(),area=fr.width*fr.height;
                  if(area>largest){largest=area;frame=frames[fi];}
                }
                if(frame){if(window.__nyamaTvCaptions)window.__nyamaTvCaptions.choice='';frame.focus();return reply({ok:true,provider:'focus'});}
                return reply({ok:false});
              }
              for(var d=0;d<documents.length;d++){
                var found=documents[d].querySelectorAll('video');
                for(var vi=0;vi<found.length;vi++){
                  var v=found[vi],rect=v.getBoundingClientRect();
                  if(rect.width>0&&rect.height>0&&v.readyState>=1&&isFinite(v.duration)&&v.duration>0)videos.push(v);
                }
              }
              if(videos.length!==1)return reply({ok:false});
              var video=videos[0],source=video.currentSrc||'',s=window.__nyamaTvCaptions;
              if(!s||s.video!==video){
                s={video:video,source:source,token:Date.now().toString(36)+Math.random().toString(36).slice(2),
                   entries:[],originals:new WeakMap(),serial:0,choice:''};window.__nyamaTvCaptions=s;
              }else if(s.source!==source){
                // A quality/source switch invalidates old commands, but retained cue objects
                // still need their original baseline so offsets can never accumulate.
                s.source=source;s.token=Date.now().toString(36)+Math.random().toString(36).slice(2);
              }
              function caption(t){return t.kind==='subtitles'||t.kind==='captions';}
              function entry(t){
                for(var i=0;i<s.entries.length;i++)if(s.entries[i].track===t)return s.entries[i];
                var e={track:t,key:'track-'+(++s.serial),offset:0,local:''};s.entries.push(e);return e;
              }
              function nodeFor(t){
                var nodes=video.querySelectorAll('track');
                for(var n=0;n<nodes.length;n++)if(nodes[n].track===t)return nodes[n];return null;
              }
              function original(cue){
                var saved=s.originals.get(cue);
                if(!saved){saved={start:Number(cue.startTime),end:Number(cue.endTime),text:String(cue.text||'')};s.originals.set(cue,saved);}
                return saved;
              }
              function cuesFor(e){
                var cues=e.track.cues;if(!cues||!cues.length||cues.length>6000)return null;
                for(var i=0;i<cues.length;i++){
                  var c=cues[i];if(typeof c.text!=='string'||c.text.length>8192||!isFinite(c.startTime)||!isFinite(c.endTime))return null;
                }
                return cues;
              }
              function shift(e,delta){
                var cues=cuesFor(e),node=nodeFor(e.track);
                if(!cues||(!e.local&&(!node||node.readyState!==2)))return false;
                var before=[];
                for(var i=0;i<cues.length;i++){
                  var base=original(cues[i]);if(base.end<=base.start||base.start<0)return false;
                  before.push([cues[i].startTime,cues[i].endTime,cues[i].text]);
                }
                try{
                  for(var j=0;j<cues.length;j++){
                    var b=original(cues[j]),start=Math.max(0,b.start+delta),end=Math.max(0.001,b.end+delta);
                    cues[j].text=b.end+delta<=0?'':b.text;
                    // Set the end first when moving forward, and the start first when moving back.
                    if(end>=cues[j].endTime){cues[j].endTime=end;cues[j].startTime=start;}
                    else{cues[j].startTime=start;cues[j].endTime=end;}
                  }
                }catch(readOnly){
                  for(var k=0;k<before.length;k++){
                    try{cues[k].text=before[k][2];}catch(ignoredText){}
                    try{
                      if(before[k][1]>=cues[k].endTime){cues[k].endTime=before[k][1];cues[k].startTime=before[k][0];}
                      else{cues[k].startTime=before[k][0];cues[k].endTime=before[k][1];}
                    }catch(ignoredTiming){}
                  }
                  return false;
                }
                e.offset=delta;return true;
              }
              var tracks=video.textTracks;if(!tracks||tracks.length>256)return reply({ok:false});
              if(!video.__nyamaSubtitleOffWatch&&tracks.addEventListener){
                video.__nyamaSubtitleOffWatch=true;
                function keepOff(){
                  var active=window.__nyamaTvCaptions;
                  if(!active||active.video!==video||active.choice!=='off')return;
                  for(var oi=0;oi<video.textTracks.length;oi++){
                    var ot=video.textTracks[oi];if(caption(ot)&&ot.mode!=='disabled')ot.mode='disabled';
                  }
                }
                tracks.addEventListener('addtrack',keepOff);tracks.addEventListener('change',keepOff);
              }
              s.entries=s.entries.filter(function(e){for(var ti=0;ti<tracks.length;ti++)if(tracks[ti]===e.track)return true;return false;});
              var list=[];for(var t=0;t<tracks.length;t++)if(caption(tracks[t]))list.push(entry(tracks[t]));
              if(list.length>32)return reply({ok:false});
              var command=__COMMAND__,token=__TOKEN__,selected=null;
              if(command!=='inspect'&&token!==s.token)return reply({ok:false,stale:true});
              for(var e=0;e<list.length;e++)if(list[e].key===__KEY__)selected=list[e];
              if(command==='select'){
                if(!selected)return reply({ok:false});s.choice='';
                for(var a=0;a<list.length;a++)list[a].track.mode=list[a]===selected?'showing':'disabled';
                video.__nyamaSubtitleManual=true;
              }else if(command==='off'){
                s.choice='off';
                for(var o=0;o<list.length;o++)list[o].track.mode='disabled';video.__nyamaSubtitleManual=true;
              }else if(command==='auto'){
                s.choice='';
                for(var z=0;z<list.length;z++){
                  if(list[z].offset!==0&&!shift(list[z],0))return reply({ok:false});
                  list[z].track.mode='disabled';
                }
                delete video.__nyamaSubtitleManual;delete video.__nyamaAutoSubtitle;
              }else if(command==='offset'){
                var delta=__OFFSET__,shown=0;
                for(var sc=0;sc<list.length;sc++)if(list[sc].track.mode==='showing')shown++;
                if(shown!==1||!selected||selected.track.mode!=='showing'||!isFinite(delta)||Math.abs(delta)>5
                    ||Math.abs(delta*2-Math.round(delta*2))>0.001||!shift(selected,delta))return reply({ok:false});
                video.__nyamaSubtitleManual=true;
              }else if(command==='import'){
                s.choice='';
                var file=__IMPORT__,owner=video.ownerDocument.defaultView;
                if(!file||!file.cues||!file.cues.length||file.cues.length>6000||!owner||!owner.VTTCue||!video.addTextTrack)return reply({ok:false});
                selected=null;
                for(var l=0;l<list.length;l++)if(list[l].local===file.id)selected=list[l];
                if(!selected){
                  var prepared=[];
                  for(var ci=0;ci<file.cues.length;ci++){
                    var q=file.cues[ci];if(!isFinite(q[0])||!isFinite(q[1])||q[0]<0||q[1]<=q[0]||typeof q[2]!=='string'||q[2].length>8192)return reply({ok:false});
                    var text=q[2].replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
                    prepared.push(new owner.VTTCue(q[0],q[1],text));
                  }
                  var local=video.addTextTrack('subtitles',file.label,file.language);selected=entry(local);selected.local=file.id;
                  try{for(var pc=0;pc<prepared.length;pc++)local.addCue(prepared[pc]);}
                  catch(unsupported){local.mode='disabled';return reply({ok:false});}
                  list.push(selected);
                }
                for(var it=0;it<list.length;it++)list[it].track.mode=list[it]===selected?'showing':'disabled';
                video.__nyamaSubtitleManual=true;
              }
              var result=[],signature='',selectedCount=0;
              for(var count=0;count<list.length;count++)if(list[count].track.mode==='showing')selectedCount++;
              for(var ri=0;ri<list.length;ri++){
                var item=list[ri],track=item.track,node=nodeFor(track),cues=cuesFor(item),show=track.mode==='showing';
                var stable=!!item.local||!!(node&&node.readyState===2);
                if(cues){
                  if(item.firstCue&&(item.firstCue!==cues[0]||item.cueCount!==cues.length))item.offset=0;
                  item.firstCue=cues[0];item.cueCount=cues.length;
                }
                if(show&&cues&&stable&&selectedCount===1){
                  var raw=[],budget=0;
                  for(var si=0;si<cues.length;si++){
                    var base=original(cues[si]);budget+=base.text.length+64;
                    if(budget>1048576)break;
                    raw.push([base.start,base.end,base.text]);
                  }
                  if(budget<=1048576){var candidate=JSON.stringify(raw);if(candidate.length<=1048576)signature=candidate;}
                }
                var address=node?String(node.src||''):'';if(address.length>8192)address='';
                result.push({key:item.key,label:String(track.label||'').slice(0,160),language:String(track.language||'').slice(0,32),
                  kind:track.kind,selected:show,source:address,local:item.local,
                  canOffset:show&&!!cues&&stable,offset:item.offset});
              }
              var showing=0;for(var rc=0;rc<result.length;rc++)if(result[rc].selected)showing++;
              if(showing>1){signature='';for(var ro=0;ro<result.length;ro++)result[ro].canOffset=false;}
              return reply({ok:true,token:s.token,tracks:result,signature:signature,
                manual:!!video.__nyamaSubtitleManual,canImport:!!(video.addTextTrack&&video.ownerDocument.defaultView.VTTCue)});
            }catch(error){return JSON.stringify({ok:false});}})()
            """;
        return code.replace("__ORIGIN__", quote(origin)).replace("__PATH__", quote(path))
                .replace("__COMMAND__", quote(command)).replace("__TOKEN__", quote(token))
                .replace("__KEY__", quote(key)).replace("__OFFSET__", Double.toString(offset))
                .replace("__IMPORT__", importedJson == null ? "null" : importedJson.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029"));
    }

    static String quote(String value) {
        return JSONObject.quote(value == null ? "" : value).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }
}
