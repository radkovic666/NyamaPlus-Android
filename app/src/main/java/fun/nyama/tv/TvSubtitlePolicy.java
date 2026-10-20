package fun.nyama.tv;

import org.json.JSONObject;

/** Operates only on accessible HTML5 tracks. Cross-origin provider controls remain untouched. */
final class TvSubtitlePolicy {
    static String script(String videoDiscovery, String id, int season, int episode, boolean english) {
        String policy = """
            if(!best||!best.textTracks)return '0';
            var expected=__ID__,season=__SEASON__,episode=__EPISODE__,language=__LANG__;
            function score(t){
              if(t.kind!=='subtitles'&&t.kind!=='captions')return -1;
              if(!new RegExp('^('+language+')(-|$)','i').test(t.language||''))return -1;
              var nodes=best.querySelectorAll?best.querySelectorAll('track'):[],el=null;
              for(var n=0;n<nodes.length;n++)if(nodes[n].track===t)el=nodes[n];
              var label=(t.label||'')+' '+(el?(el.getAttribute('src')||''):'');
              var imdb=label.match(/tt[0-9]{7,10}/i),s=null,e=null;
              if(el){
                var explicit=el.getAttribute('data-imdb-id');if(explicit)imdb=[explicit];
                s=el.getAttribute('data-season');e=el.getAttribute('data-episode');
              }
              if(imdb&&imdb[0].toLowerCase()!==expected)return -1;
              var numbered=label.match(/s(\\d{1,3})[ ._-]*e(\\d{1,4})(?!\\d)/i);
              if(numbered){s=numbered[1];e=numbered[2];}
              var sq=label.match(/[?&]season=(\\d+)/i),eq=label.match(/[?&]episode=(\\d+)/i);
              if(sq)s=sq[1];if(eq)e=eq[1];
              if(season>=0&&((s!==null&&s!==''&&Number(s)!==season)||(e!==null&&e!==''&&Number(e)!==episode)))return -1;
              return (imdb?2:0)+(season>=0&&s!==null&&s!==''&&e!==null&&e!==''?4:0);
            }
            function choose(){
              if(best.readyState<1)return;
              var old=best.__nyamaAutoSubtitle;
              if(old&&old.track.mode!=='showing')return;
              for(var k=0;k<best.textTracks.length;k++){
                var shown=best.textTracks[k];
                if(shown.mode==='showing'&&(!old||shown!==old.track))return;
              }
              var found=null,rank=-1,tied=false;
              for(var i=0;i<best.textTracks.length;i++){
                var t=best.textTracks[i],r=score(t);
                if(r>rank){found=t;rank=r;tied=false;}else if(r>=0&&r===rank)tied=true;
              }
              if(!found||tied||(old&&rank<=old.rank))return;
              for(var j=0;j<best.textTracks.length;j++)best.textTracks[j].mode=best.textTracks[j]===found?'showing':'disabled';
              best.__nyamaAutoSubtitle={track:found,rank:rank};
            }
            if(!best.__nyamaSubtitleWatch){
              best.__nyamaSubtitleWatch=true;
              best.addEventListener('loadedmetadata',choose);
              if(best.textTracks.addEventListener)best.textTracks.addEventListener('addtrack',choose);
            }
            choose();return '1';
            """;
        policy = policy.replace("__ID__", JSONObject.quote(id)).replace("__SEASON__", Integer.toString(season))
                .replace("__EPISODE__", Integer.toString(episode)).replace("__LANG__", JSONObject.quote(english ? "en|eng" : "bg|bul"));
        return "(function(){try{" + videoDiscovery + policy + "}catch(e){return '0';}})();";
    }
}
