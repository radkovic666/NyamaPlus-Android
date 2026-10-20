package fun.nyama.tv;

import org.json.JSONObject;

/** A single initial HTML5 start in the selected document; never guesses provider controls. */
final class Player1InitialStart {
    private Player1InitialStart() {}

    static String cancelScript() {
        return "(function(){if(window.__nyamaInitialStart)window.__nyamaInitialStart.cancel();})();";
    }

    static String script(String expectedPath) {
        return """
            (function(expected){
              if(location.pathname!==expected||window.__nyamaInitialStart)return;
              var stopped=false,scheduled=false,timer=null,watched=[],observers=[];
              function stop(){
                if(stopped)return;stopped=true;clearTimeout(timer);
                observers.forEach(function(o){o.disconnect();});
                watched.forEach(function(d){
                  ['pointerdown','touchstart','keydown'].forEach(function(k){d.removeEventListener(k,interaction,true);});
                  ['loadeddata','canplay','playing','load'].forEach(function(k){d.removeEventListener(k,schedule,true);});
                });
              }
              window.__nyamaInitialStart={cancel:stop};
              function interaction(e){if(e.isTrusted)stop();}
              function visible(element,d){
                var r=element.getBoundingClientRect(),s=d.defaultView.getComputedStyle(element);
                return r.width>=80&&r.height>=45&&r.right>0&&r.bottom>0
                  &&r.left<d.defaultView.innerWidth&&r.top<d.defaultView.innerHeight
                  &&s.display!=='none'&&s.visibility!=='hidden'&&s.opacity!=='0';
              }
              function collect(d,found){try{
                if(!d||d.location.origin!==location.origin)return;
                if(watched.indexOf(d)<0){
                  watched.push(d);
                  ['pointerdown','touchstart','keydown'].forEach(function(k){d.addEventListener(k,interaction,{capture:true,passive:true});});
                  ['loadeddata','canplay','playing','load'].forEach(function(k){d.addEventListener(k,schedule,true);});
                  var observer=new MutationObserver(schedule);
                  observer.observe(d.documentElement,{childList:true,subtree:true,attributes:true,
                    attributeFilter:['class','style','hidden']});observers.push(observer);
                }
                var videos=d.querySelectorAll('video');
                for(var i=0;i<videos.length;i++){
                  var v=videos[i];if(visible(v,d))found.push(v);
                }
                var frames=d.querySelectorAll('iframe');
                for(var j=0;j<frames.length;j++)try{if(visible(frames[j],d))collect(frames[j].contentDocument,found);}catch(ignored){}
              }catch(ignored){}}
              function attempt(){
                scheduled=false;if(stopped)return;
                if(location.pathname!==expected){stop();return;}
                var videos=[];collect(document,videos);
                // Ambiguous visible players and inaccessible frames cannot be safely guessed.
                if(videos.length!==1)return;
                var v=videos[0];
                if(!v.paused||v.ended){stop();return;}
                if(v.readyState<2)return;
                stop(); // Disconnect before play so later pause/ready events cannot restart it.
                try{var result=v.play();if(result&&result.catch)result.catch(function(){});}catch(ignored){}
              }
              function schedule(){if(!stopped&&!scheduled){scheduled=true;requestAnimationFrame(attempt);}}
              timer=setTimeout(stop,30000);
              attempt();
            })(__NYAMA_EXPECTED_PATH__);
            """.replace("__NYAMA_EXPECTED_PATH__", JSONObject.quote(expectedPath));
    }
}
