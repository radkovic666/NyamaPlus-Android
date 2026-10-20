package fun.nyama.tv;

import android.view.View;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;

/** Fit accessible video pixels without resizing provider controls or accessing protected frames. */
final class PlayerVideoFit {
    private PlayerVideoFit() {}

    static Insets safeInsets(WindowInsetsCompat insets) {
        return Insets.max(insets.getInsets(WindowInsetsCompat.Type.systemBars()),
                insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout()));
    }

    static WindowInsetsCompat applyInsets(View view, WindowInsetsCompat insets) {
        Insets safe = safeInsets(insets);
        view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
        return insets.inset(safe.left, safe.top, safe.right, safe.bottom);
    }

    static String script() {
        return """
            (function(){
              function fit(d){try{
                if(!d)return;
                if(!d.getElementById('__nyama_video_fit')){
                  var style=d.createElement('style');style.id='__nyama_video_fit';
                  style.textContent='video{object-fit:contain!important;object-position:50% 50%!important;}'
                    +'video:fullscreen,video:-webkit-full-screen{width:100%!important;height:100%!important;}';
                  (d.head||d.documentElement).appendChild(style);
                }
                if(!d.__nyamaVideoFit){
                  d.__nyamaVideoFit=true;
                  d.addEventListener('load',function(e){
                    if(e.target&&e.target.tagName==='IFRAME'){
                      try{fit(e.target.contentDocument);}catch(ignored){}
                    }
                  },true);
                }
                var frames=d.querySelectorAll('iframe');
                for(var i=0;i<frames.length;i++){
                  try{fit(frames[i].contentDocument);}catch(ignored){}
                }
              }catch(ignored){}}
              fit(document);
            })();
            """;
    }
}
