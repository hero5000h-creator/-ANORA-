/* Smoke test run on every push before the APK is built. It catches the kind of mistake that reached a
   test build once: recitation that stops right after it starts. Run: node tests/smoke.js (after npm run build) */
const {chromium}=require("playwright");const fs=require("fs"),path=require("path"),http=require("http");
const WWW=path.join(__dirname,"..","www"),TONE=fs.readFileSync(path.join(__dirname,"tone.mp3"));
const src=fs.readFileSync(path.join(WWW,"index.html"),"utf8"),hook='recUI();audData(reciter).then(d=>{if(!aud)aud=d});';
if(!src.includes(hook))throw new Error("test hook point not found in www/index.html");
fs.writeFileSync(path.join(WWW,"smoke.html"),src.replace(hook,hook+"window.__smk={audData,playFrom,P:()=>player,aud:()=>aud,AU,setPlaying,SST,openAyah,nativeAudio:!(AU instanceof HTMLMediaElement),qY,AW,NA,setRate,rcss:rasterCSS,turn:{pick:turnPick,rasterPage,setupFlip,render,finish,cur:()=>cur,mode:()=>mode,W:()=>W}};"));
const types={".html":"text/html",".js":"text/javascript",".json":"application/json",".woff2":"font/woff2",".ttf":"font/ttf",".css":"text/css"};
const srv=http.createServer((q,r)=>{const f=path.join(WWW,decodeURIComponent(q.url.split("?")[0]));
  fs.readFile(f,(e,b)=>{if(e){r.writeHead(404);r.end();return}r.writeHead(200,{"content-type":types[path.extname(f)]||"application/octet-stream"});r.end(b)})}).listen(0);
const fails=[];const ok=(c,m)=>{console.log((c?"ok   ":"FAIL ")+m);if(!c)fails.push(m)};
/* general layout check: every open panel or toast is on screen, actually on top (not hidden behind the page),
   and a toast never sits on the Quran text */
const LAYOUT=(want)=>{const out=[],vw=innerWidth,vh=innerHeight,vis=e=>e&&!e.hidden&&getComputedStyle(e).display!=="none"&&e.getBoundingClientRect().width>0;
  for(const id of ["prefs","repBox","srcBox","sheet"]){const e=document.getElementById(id);if(!vis(e))continue;const r=e.getBoundingClientRect();
    if(r.left<-1||r.top<-1||r.right>vw+1||r.bottom>vh+1)out.push(id+" off screen");
    const x=Math.min(vw-2,Math.max(1,r.left+r.width/2)),y=Math.min(vh-2,Math.max(1,r.top+Math.min(40,r.height/2)));const h=document.elementFromPoint(x,y);
    if(!h||!e.contains(h))out.push(id+" covered by "+(h?(h.id||h.className||h.tagName):"nothing"))}
  const t=document.getElementById("toast");if(t&&t.classList.contains("on")){const r=t.getBoundingClientRect();
    const fl=document.getElementById("flow"),flOn=vis(fl);/* the book lies hidden under the flow text while it is open */
    if([...document.querySelectorAll(flOn?"#flow [data-a]":"#book .pg [data-a]")].some(w=>{const q=w.getBoundingClientRect();return q.width&&q.right>r.left&&q.left<r.right&&q.bottom>r.top+2&&q.top<r.bottom-2}))out.push("toast over Quran text")}
  for(const id of want||[]){const e=document.getElementById(id);if(!vis(e)&&!(id==="toast"&&e&&e.classList.contains("on")))out.push(id+" expected but not showing")}
  return out};
const lay=async(p,m,want)=>{const o=await p.evaluate(LAYOUT,want||[]);ok(!o.length,`${m}: layout clean ${o.join(", ")}`)};
(async()=>{const port=srv.address().port;
  const b=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||undefined,args:["--autoplay-policy=no-user-gesture-required"]});
  for(const [name,vp] of [["phone",{width:412,height:891}],["fold",{width:1000,height:760}]]){
    const ctx=await b.newContext({viewport:vp,hasTouch:true,isMobile:true});const p=await ctx.newPage();const errs=[];
    p.on("pageerror",e=>errs.push(e.message));
    await p.addInitScript(()=>{localStorage.setItem("mushaf.last","50");localStorage.setItem("mushaf.hintN","9");localStorage.setItem("mushaf.easyQ","1");window.__L={};
      /* the native player (AudioPlugin.kt) stood in for by an <audio> that speaks the plugin's events */
      const MA=new Audio(),em=(e,d)=>{const f=window.__L["Audio"+e];f&&f(d||{})},tm=()=>em("time",{ms:MA.currentTime*1000,playing:!MA.paused&&!MA.ended});
      setInterval(()=>{if(!MA.paused)tm()},200);MA.addEventListener("playing",tm);MA.addEventListener("pause",tm);MA.addEventListener("seeked",tm);
      MA.addEventListener("ended",()=>em("ended"));MA.addEventListener("loadedmetadata",()=>em("loaded",{dur:MA.duration*1000}));MA.addEventListener("error",()=>em("error",{msg:"x"}));window.__MA=MA;
      const AP={load:o=>{MA.src=o.url},play:()=>MA.play(),pause:()=>MA.pause(),seek:o=>{MA.currentTime=o.ms/1000},rate:o=>{MA.playbackRate=o.rate},stop:()=>{MA.pause();MA.removeAttribute("src")}};
      window.Capacitor={isNativePlatform:()=>true,getPlatform:()=>"android",convertFileSrc:u=>u,isPluginAvailable:n=>n==="Audio",addListener:(pl,ev,fn)=>{window.__L[pl+ev]=fn},
        nativePromise:(pl,m,o)=>pl==="Widget"?Promise.resolve(m==="update"?(window.__wg=o,{}):m==="take"?(()=>{const g=window.__wgGo||null;window.__wgGo=null;return {go:g}})():{ok:false}):pl==="Audio"?Promise.resolve().then(()=>AP[m](o||{})).then(()=>({})):pl==="Playback"&&m==="ack"?(window.__ack=o.n,Promise.resolve({})):m==="getUri"?Promise.resolve({uri:"file:///d/"+o.path}):Promise.resolve({})}});
    await p.route(/\.mp3(\?|$)/,r=>{const h=r.request().headers().range,B=TONE;if(h){const m=/bytes=(\d+)-(\d*)/.exec(h),a=+m[1],e=m[2]?+m[2]:B.length-1;
      return r.fulfill({status:206,headers:{"content-type":"audio/mpeg","accept-ranges":"bytes","content-range":`bytes ${a}-${e}/${B.length}`},body:B.subarray(a,e+1)})}
      r.fulfill({status:200,headers:{"content-type":"audio/mpeg","accept-ranges":"bytes"},body:B})});
    await p.goto(`http://localhost:${port}/smoke.html`);await p.waitForTimeout(4000);
    /* page turn: the page images for the curl must render (an invalid SVG once made every turn a flat fold),
       the curl / 3D spread must be the one used, and every turn style must move exactly one page (spread: two) */
    {const dflt=await p.evaluate(()=>__smk.turn.pick()+(document.querySelector("#book canvas")?" canvas":""));
      ok(dflt==="slide",`${name}: pages glide by default (${dflt})`);
      {const L=await p.evaluate(()=>({warm:getComputedStyle(document.getElementById("warm")).display,dim:getComputedStyle(document.getElementById("dimV")).display,drawer:getComputedStyle(document.getElementById("drawer")).visibility}));
        ok(L.warm==="none"&&L.dim==="none"&&L.drawer==="hidden",`${name}: hidden full-screen layers are not drawn (${JSON.stringify(L)})`)}
      const r=await p.evaluate(async()=>{localStorage.setItem("mushaf.turn","curl");const T=__smk.turn,c0=T.cur();const img=await T.rasterPage(c0);await T.rasterPage(c0+(T.mode()==="spread"?2:1));
        const fontsIn=!/url\(["']?fonts\//.test(__smk.rcss());const step=T.mode()==="spread"?2:1;const f=T.setupFlip("fwd",c0+step);const kind=f.b3?"3d":f.curl?"curl":"flat";T.finish(f,false);return {img:!!img,kind,c0,step,fontsIn}});
      ok(r.img,`${name}: page image for the curl renders`);ok(r.fontsIn,`${name}: the turning page image carries the mushaf fonts (no font file left to load)`);ok(r.kind===(name==="fold"?"3d":"curl"),`${name}: turn uses the ${name==="fold"?"3D spread":"curl"} (${r.kind})`);
      const bb=await p.locator("#book").boundingBox(),y=bb.y+bb.height*.6;
      for(const st of ["book","curl","slide","none"]){await p.evaluate(s=>localStorage.setItem("mushaf.turn",s),st);
        const c0=await p.evaluate(()=>__smk.turn.cur());
        await p.mouse.move(bb.x+bb.width*.2,y);await p.mouse.down();for(let k=1;k<=10;k++){await p.mouse.move(bb.x+bb.width*(.2+k*.06),y);await p.waitForTimeout(16)}await p.mouse.up();await p.waitForTimeout(1100);
        const c1=await p.evaluate(()=>__smk.turn.cur());ok(c1===c0+r.step,`${name}: swipe turns one ${r.step>1?"spread":"page"} with "${st}" (${c0}→${c1})`);
        await p.evaluate(()=>{document.dispatchEvent(new KeyboardEvent("keydown",{key:"ArrowRight"}))});await p.waitForTimeout(1100)}
      await p.evaluate(()=>localStorage.removeItem("mushaf.turn"))}
    ok(!errs.length,`${name}: no script errors on load ${errs.join(" | ")}`);
    /* offline from the first start: no font or style comes from the internet, and the Quran text font of the
       index, sheet and search is the app's own file */
    {const r=await p.evaluate(async()=>{await document.fonts.load('20px "Scheherazade New"',"بِسۡمِ");return {ext:[...document.querySelectorAll('link[rel=stylesheet],link[rel=preconnect]')].map(l=>l.href).filter(h=>/^https?:/.test(h)&&!h.includes(location.host)),sch:document.fonts.check('20px "Scheherazade New"',"بِسۡمِ")&&[...document.fonts].some(f=>f.family.replace(/"/g,"")==="Scheherazade New"&&f.status==="loaded")}});
      ok(!r.ext.length&&r.sch,`${name}: fonts are bundled, nothing loaded from the internet ${JSON.stringify(r)}`)}
    /* any device: letters drawn 15% bigger after the page measured them (a phone's font size setting) never run past the frame */
    {const over=()=>p.evaluate(()=>{let n=0;document.querySelectorAll("#book>.pg:not(.pk):not(.blank)").forEach(pg=>{const bx=pg.querySelector(".box"),br=bx.getBoundingClientRect(),bl=parseFloat(getComputedStyle(bx).borderLeftWidth)||0;
       pg.querySelectorAll(".lb").forEach(l=>{const r=l.getBoundingClientRect();if(r.left<br.left+bl*.5-1||r.right>br.right-bl*.5+1)n++})});return n});
     const a=await over();await p.evaluate(()=>{const st=document.createElement("style");st.id="smkZ";st.textContent="#book .lines{zoom:1.15}";document.head.appendChild(st)});
     const b=await over();await p.evaluate(()=>window.__fitGuard());await p.waitForTimeout(1500);const c=await over();
     await p.evaluate(()=>{document.getElementById("smkZ").remove();window.__fitGuard()});await p.waitForTimeout(800);
     ok(a===0&&b>0&&c===0,`${name}: lines stay inside the frame when the device scales text (${a} → ${b} → ${c})`)}
    /* speed: no :has() selector on the page (one on #app re-styled every word of every kept page after each turn);
       the plain classes standing in for it follow the settings menu opening and closing */
    {const r=await p.evaluate(async()=>{const W=z=>new Promise(r=>setTimeout(r,z));let has=0;for(const ss of document.styleSheets){try{for(const x of ss.cssRules)if(x.cssText.includes(":has("))has++}catch(e){}}
        const A=document.getElementById("app");A.classList.remove("calm");document.getElementById("bPrefs").click();await W(400);const on=A.classList.contains("hPrefs");
        window.anoraBack();await W(500);const off=!A.classList.contains("hPrefs")&&document.getElementById("prefs").hidden;return {has,on,off}});
      ok(!r.has&&r.on&&r.off,`${name}: no :has() rules; settings-open state kept as a class (${JSON.stringify(r)})`)}
    /* Android back: closes the open layer first (index, then settings), and only says "nothing open" when nothing is */
    {const r=await p.evaluate(async()=>{const W=z=>new Promise(r=>setTimeout(r,z));document.getElementById("bIndex").click();await W(500);
        const a=window.anoraBack(),closed=!document.getElementById("drawer").classList.contains("on");await W(500);
        document.getElementById("bPrefs").click();await W(300);const b=window.anoraBack(),pc=document.getElementById("prefs").hidden;await W(500);
        const c=window.anoraBack(),warn=document.getElementById("toast").textContent;const d=window.anoraBack();await W(2200);const e=window.anoraBack();
        document.getElementById("app").classList.remove("calm");document.getElementById("bPrefs").click();await W(400);document.getElementById("bSrc").click();await W(600);
        const f=window.anoraBack();await W(700);const src=!document.getElementById("srcBox").hidden,pr=!document.getElementById("prefs").hidden;window.anoraBack();await W(500);
        return {a,closed,b,pc,c,warn,d,e,f,src,pr}});
      ok(r.a&&r.closed&&r.b&&r.pc&&r.c===true&&/مرة ثانية/.test(r.warn)&&r.d===false&&r.e===true,`${name}: back closes the index, then settings; on the page it warns once and a second press leaves (${JSON.stringify(r)})`);
      ok(r.f&&!r.src&&r.pr,`${name}: back from the sources returns to settings (${JSON.stringify(r)})`)}
    /* the search button drops a search panel (not the index); a word finds its ayat, and back closes it */
    {const r=await p.evaluate(async()=>{const W=z=>new Promise(r=>setTimeout(r,z));document.getElementById("app").classList.remove("calm");const b=document.getElementById("bSrch");(b.offsetParent?b:document.getElementById("fSrch")).click();await W(400);
        const d=document.getElementById("sDrop"),open=!d.hidden,idx=document.getElementById("drawer").classList.contains("on"),q=document.getElementById("sq"),focus=document.activeElement===q;
        q.value="الصبر";q.dispatchEvent(new Event("input"));await W(700);const n=document.querySelectorAll("#sres [data-a]").length;
        q.value="الكتاب";q.dispatchEvent(new Event("input"));await W(700);const nAll=document.querySelectorAll("#sres [data-a]").length;
        document.getElementById("sdSur").click();await W(500);const only=[...document.querySelectorAll("#sres [data-a]")].map(e=>+e.dataset.a);
        const S=__smk.SST,su=id=>S.filter(x=>x<=id).length,inSur=only.length>0&&only.every(id=>su(id)===su(only[0]));
        document.querySelector('[data-scope="all"]').click();await W(300);const back=window.anoraBack();await W(400);return {open,idx,focus,n,back,closed:d.hidden,nAll,only:only.length,inSur}});
      ok(r.open&&!r.idx&&r.focus&&r.n>=3&&r.only<r.nAll&&r.inSur&&r.back&&r.closed,`${name}: search drops its own panel with results, back closes it (${JSON.stringify(r)})`)}
    /* back closes the quiz instead of sending the app away */
    {const r=await p.evaluate(async()=>{const W=z=>new Promise(r=>setTimeout(r,z));document.getElementById("bIndex").click();await W(500);document.getElementById("t-r").click();await W(300);
        document.getElementById("qzOpen").click();await W(700);const open=document.getElementById("quiz").classList.contains("on");const back=window.anoraBack();await W(600);
        const idx=document.getElementById("drawer").classList.contains("on");window.anoraBack();await W(500);return {open,back,closed:document.getElementById("quiz").hidden,idx,idxClosed:!document.getElementById("drawer").classList.contains("on")}});
      ok(r.open&&r.back&&r.closed&&r.idx&&r.idxClosed,`${name}: back closes the quiz and returns to the index it came from (${JSON.stringify(r)})`)}
    /* the reciter picker lists every reciter and its search box narrows the list (hamza and spacing don't matter) */
    {const r=await p.evaluate(async()=>{const W=z=>new Promise(r=>setTimeout(r,z));document.getElementById("recSel").click();await W(400);
        const vis=()=>[...document.querySelectorAll("#rpL .rp-o")].filter(o=>!o.hidden).length,all=vis(),q=document.getElementById("rpQ");
        q.value="السديس";q.dispatchEvent(new Event("input"));const one=vis();q.value="مجود";q.dispatchEvent(new Event("input"));const muj=vis();
        q.value="اسماعيل";q.dispatchEvent(new Event("input"));const hz=vis();q.value="زززز";q.dispatchEvent(new Event("input"));const none=vis(),msg=!document.getElementById("rpN").hidden;
        const back=window.anoraBack();await W(400);return {all,one,muj,hz,none,msg,back,closed:document.getElementById("recPick").hidden}});
      ok(r.all>=30&&r.one===1&&r.muj>=4&&r.hz===1&&r.none===0&&r.msg&&r.back&&r.closed,`${name}: reciter picker search narrows the list (${JSON.stringify(r)})`)}
    /* timing sets with missing ayat (al-Hudhaifi lacks most last ayat, al-Qasim three in a row) are filled, in order */
    {const r=await p.evaluate(async()=>{const out={};for(const k of ["hudhaifi","qasim","husary"]){const d=await __smk.audData(k);let miss=0,ord=0;
        for(let s=1;s<=114;s++){const rows=d.surahs[s].filter(x=>x[0]>0);const n=__smk.SST[s]!==undefined?__smk.SST[s]-__smk.SST[s-1]:6236-__smk.SST[s-1]+1;
          if(rows.length!==n)miss++;for(let i=1;i<rows.length;i++)if(rows[i][1]<rows[i-1][1]||rows[i][0]!==rows[i-1][0]+1)ord++}out[k]={miss,ord}}return out});
      ok(Object.values(r).every(v=>!v.miss&&!v.ord),`${name}: ayah timings with gaps are filled in order (${JSON.stringify(r)})`)}
    /* home-screen widgets: the page sends what they show, and a tap on one opens the screen it names */
    {const w=await p.evaluate(()=>window.__wg||null);
      ok(w&&w.page>=1&&w.sur&&w.cats&&w.cats.sab&&w.cats.sab.of>0&&w.cats.sab.f0&&w.cats.sab.f0.t&&Array.isArray(w.pt)&&typeof w.ptc==="string"&&w.free.length,`${name}: widget data sent (page ${w&&w.page}, ${w&&w.sur}, ${w&&w.cats&&w.cats.sab&&w.cats.sab.of} morning adhkar)`);
      const r=await p.evaluate(async()=>{const W=z=>new Promise(r=>setTimeout(r,z));window.__wgGo="az:mas";window.__L.Widgetopen({});await W(900);
        const az=!document.getElementById("az").hidden,tt=document.getElementById("azTitle").textContent;window.__wgGo="page:77";window.__L.Widgetopen({});await W(1600);
        return {az,tt,closed:document.getElementById("az").hidden,cur:__smk.turn.cur()}});
      ok(r.az&&r.tt==="أذكار المساء"&&r.closed&&(r.cur===77||r.cur===76),`${name}: widget taps open the evening adhkar, then page 77 (${JSON.stringify(r)})`)}
    ok(await p.evaluate(()=>__smk.nativeAudio),`${name}: Android uses the native player`);
    const lock=await p.evaluate(()=>document.getElementById("qLockMsg").textContent);ok(/مطابق/.test(lock),`${name}: Quran text lock matches`);
    {const r=await p.evaluate(()=>{const S=__smk;let bad=[];for(let id=1;id<=S.NA&&bad.length<3;id++)for(const w of S.AW[id]){const q=S.qY(w);if(/ي[\u064B-\u065F\u0670\u06D6-\u06ED]*$/.test(q))bad.push(q)}
      return {bad,a:S.qY("ٱلَّذِيٓ"),b:S.qY("فِيٓ"),c:S.qY("فِيهِ")}});
      ok(!r.bad.length&&r.a==="ٱلَّذِىٓ"&&r.b==="فِىٓ"&&r.c==="فِيهِ",`${name}: final yā' is dotless everywhere, also before maddah (${r.bad.join(" ")||"ok"})`)}
    await p.evaluate(()=>__smk.playFrom(__smk.SST[2]+3));await p.waitForTimeout(3000);
    let s=await p.evaluate(()=>({on:__smk.P().on,paused:__smk.AU.paused,t:__smk.AU.currentTime}));
    ok(s.on&&!s.paused,`${name}: recitation keeps playing 3s after start (on=${s.on} paused=${s.paused})`);
    /* following the recitation must not work every screen frame (it once did, for as long as it played, and the
       phone warmed up and slowed down over a long listen), and the faded swipe hint must leave the screen */
    {const r=await p.evaluate(async()=>{let n=0;const R=window.requestAnimationFrame;window.requestAnimationFrame=f=>{n++;return R(f)};
       await new Promise(z=>setTimeout(z,1000));window.requestAnimationFrame=R;
       return {n,bar:document.getElementById("pBar").style.transform,anims:document.getAnimations().filter(a=>a.playState==="running"&&a.effect.getTiming().iterations===Infinity).length}});
     ok(r.n<15&&/scaleX/.test(r.bar),`${name}: reciting asks for few frames (${r.n} in 1s) and moves the bar by transform`);
     ok(!r.anims,`${name}: nothing animates forever while reading (${r.anims})`)}
    const t1=s.t;await p.waitForTimeout(1200);s=await p.evaluate(()=>({t:__smk.AU.currentTime}));ok(s.t>t1+.5,`${name}: audio time moves (${t1.toFixed(1)} → ${s.t.toFixed(1)})`);
    await p.evaluate(()=>window.__L.Playbackaction&&window.__L.Playbackaction({a:"pause"}));await p.waitForTimeout(300);
    ok(await p.evaluate(()=>__smk.AU.paused&&!__smk.P().on),`${name}: notification pause pauses`);
    await p.evaluate(()=>window.__L.Playbackaction&&window.__L.Playbackaction({a:"play"}));await p.waitForTimeout(800);
    ok(await p.evaluate(()=>!__smk.AU.paused&&__smk.P().on),`${name}: notification play resumes`);
    /* the next ayah follows on its own: jump near the end of this ayah and wait */
    {const r=await p.evaluate(async()=>{const id=__smk.P().id,g=__smk.P().seg;__smk.AU.currentTime=(g.end-600)/1000;await new Promise(z=>setTimeout(z,1800));return [id,__smk.P().id,__smk.P().on]});
      ok(r[1]===r[0]+1&&r[2],`${name}: recitation moves on to the next ayah (${r[0]} → ${r[1]})`)}
    /* the page fell behind the player (asleep in the background): it catches up to the ayah being heard, no jump back */
    {const r=await p.evaluate(async()=>{const P=__smk.P(),s=__smk.SST[2],rows=__smk.aud().surahs[3],row=rows.find(x=>x[0]===P.id-s+2);if(!row)return ["no row"];window.__id0=P.id;
        P.sk=0;const W=z=>new Promise(r=>setTimeout(r,z));__smk.AU.currentTime=(row[1]+400)/1000;P.sk=0;await W(1500);return [P.id-s+1,row[0],P.on]});
      ok(r[0]===r[1]&&r[2],`${name}: page catches up with the player after sleeping (${r})`)}
    /* a late notification "next" (the service already did it) is not done twice; a fresh one is confirmed */
    {const r=await p.evaluate(async()=>{const P=__smk.P(),id=P.id,W=z=>new Promise(r=>setTimeout(r,z));window.__ack=0;
        window.__L.Playbackaction({a:"next",n:5,t:Date.now()-5000});await W(300);const late=P.id;
        window.__L.Playbackaction({a:"next",n:6,t:Date.now()});await W(300);const out=[id,late,P.id,window.__ack];__smk.playFrom(window.__id0);await W(1200);return out});
      ok(r[1]===r[0]&&r[2]===r[0]+1&&r[3]===6,`${name}: late notification "next" ignored, fresh one done and confirmed (${r})`)}
    /* the system pausing the player (headphones unplugged, a call) pauses the app too */
    {const r=await p.evaluate(async()=>{window.__L.Audiohalt&&window.__L.Audiohalt({reason:3});await new Promise(z=>setTimeout(z,300));const off=!__smk.P().on;__smk.setPlaying(true);await new Promise(z=>setTimeout(z,800));return [off,__smk.P().on&&!__smk.AU.paused]});
      ok(r[0]&&r[1],`${name}: a system pause pauses the app, and play resumes (${r})`)}
    await p.evaluate(()=>document.getElementById("pRep").click());await p.waitForTimeout(700);await lay(p,`${name}: repeat panel`,["repBox"]);
    /* tapping an ayah sets the range end; tapping before "from" keeps the range within 300 */
    {const r=await p.evaluate(async()=>{const S=__smk.SST[2];const tap=async id=>{const w=[...document.querySelectorAll('#book .pg [data-a="'+id+'"]')].map(e=>e.getBoundingClientRect()).find(q=>{if(!(q.width&&q.top>0&&q.bottom<innerHeight))return false;const h=document.elementFromPoint(q.left+q.width/2,q.top+q.height/2);return h&&h.closest("#book")});
        if(!w)return false;const o={bubbles:true,clientX:w.left+w.width/2,clientY:w.top+w.height/2,pointerId:9,isPrimary:true};document.elementFromPoint(o.clientX,o.clientY).dispatchEvent(new PointerEvent("pointerdown",o));await new Promise(z=>setTimeout(z,150));return true};
      const t=await tap(S+6),to=document.getElementById("rbTo").textContent,open=!document.getElementById("repBox").hidden;return {t,to,open}});
      ok(r.t&&r.open&&/٧/.test(r.to),`${name}: tapping an ayah sets the repeat end (${r.to})`)}
    await p.evaluate(()=>document.getElementById("rbX").click());await p.waitForTimeout(400);
    await p.evaluate(()=>document.getElementById("pStop").click());await p.waitForTimeout(300);
    await p.evaluate(()=>__smk.openAyah(__smk.SST[2]+3));await p.waitForTimeout(1500);await lay(p,`${name}: ayah sheet`,["sheet"]);
    ok(await p.evaluate(()=>!document.getElementById("sheet").hidden&&/الآية ٤/.test(document.getElementById("shMeta").textContent)),`${name}: ayah sheet opens on the right ayah`);
    ok(await p.evaluate(()=>/الآيتين ٣–٤/.test((document.querySelector(".tfg")||{}).textContent||"")),`${name}: grouped tafsir is labelled`);
    await p.keyboard.press("Escape");await p.waitForTimeout(600);
    /* long-press on an ayah: the options bar floats on screen beside it; المزيد opens the full sheet */
    {const pt=await p.evaluate(()=>{const id=__smk.SST[2]+3;for(const e of document.querySelectorAll(`#book .pg:not(.pk) [data-a="${id}"]`)){const f=e.getBoundingClientRect(),x=f.left+f.width/2,y=f.top+f.height/2,h=document.elementFromPoint(x,y);if(f.width&&h&&h.closest&&h.closest("[data-a]")===e)return [x,y]}return null});
      ok(!!pt,`${name}: found the ayah to long-press`);
      if(pt){await p.mouse.move(pt[0],pt[1]);await p.mouse.down();await p.waitForTimeout(650);await p.mouse.up();await p.waitForTimeout(500);
        const r=await p.evaluate(()=>{const b=document.getElementById("lpBar"),q=b.getBoundingClientRect();return {open:!b.hidden&&b.classList.contains("on"),sheet:!document.getElementById("sheet").hidden,in:q.left>=0&&q.top>=0&&q.right<=innerWidth&&q.bottom<=innerHeight,ref:document.getElementById("lpRef").textContent}});
        ok(r.open&&!r.sheet&&r.in&&/٤/.test(r.ref),`${name}: long-press shows the options bar on screen (${r.ref})`);
        await p.evaluate(()=>document.querySelector('#lpBar [data-k="more"]').click());await p.waitForTimeout(800);
        ok(await p.evaluate(()=>document.getElementById("lpBar").hidden&&!document.getElementById("sheet").hidden),`${name}: المزيد opens the ayah sheet`);
        await p.keyboard.press("Escape");await p.waitForTimeout(600)}}
    /* opening an ayah that is not on screen turns to its page */
    {const r=await p.evaluate(async()=>{__smk.openAyah(__smk.SST[2]+40);await new Promise(z=>setTimeout(z,1200));return document.getElementById("wMeta").textContent});
      ok(/٥٥/.test(r),`${name}: ayah off screen turns to its page (${r})`);await p.keyboard.press("Escape");await p.waitForTimeout(500);
      await p.evaluate(()=>__smk.openAyah(__smk.SST[2]+3));await p.waitForTimeout(900);await p.keyboard.press("Escape");await p.waitForTimeout(600)}
    const pg0=await p.evaluate(()=>document.getElementById("wMeta").textContent);await p.keyboard.press("ArrowLeft");await p.waitForTimeout(1400);
    ok(await p.evaluate(()=>document.getElementById("wMeta").textContent)!==pg0,`${name}: page turns`);
    
    await p.evaluate(()=>{document.getElementById("app").classList.remove("calm");document.getElementById("fPrefs").click()});await p.waitForTimeout(500);
    for(const z of ["1","1.3"]){await p.evaluate(z=>document.querySelector(`[data-uz="${z}"]`).click(),z);await p.waitForTimeout(400);
      const r=await p.evaluate(()=>{const r=document.getElementById("prefs").getBoundingClientRect();return [r.top,r.bottom]});
      ok(r[0]>=-1&&r[1]<=vp.height+1,`${name}: settings panel on screen at text size ${z} (${r.map(Math.round)})`);await lay(p,`${name}: settings at text size ${z}`,["prefs"])}
    await p.keyboard.press("Escape");await p.evaluate(()=>{document.getElementById("easyBox").hidden=false;document.getElementById("easyY").click()});await p.waitForTimeout(700);await lay(p,`${name}: toast after "yes"`);await p.waitForTimeout(800);
    await p.evaluate(()=>{document.getElementById("app").classList.remove("calm");document.getElementById("fPrefs").click()});await p.waitForTimeout(600);await lay(p,`${name}: settings over large print`,["prefs"]);
    await p.evaluate(()=>document.getElementById("prefsX")&&document.getElementById("fPrefs").click());await p.keyboard.press("Escape");await p.waitForTimeout(400);
    if(name==="phone"){
      const r=await p.evaluate(()=>{const b=document.querySelector("#app>.bar").getBoundingClientRect(),f=document.querySelector(".flbar");return f&&!document.getElementById("flow").hidden?[b.bottom,f.getBoundingClientRect().top]:[1,-1]});
      ok(r[1]>=r[0]-1,`${name}: large-print toolbar starts below the top bar after "yes" (${r.map(Math.round)})`)}
    /* the visible listen button starts the recitation on this page; a removed mark can be put back */
    {await p.keyboard.press("Escape");await p.waitForTimeout(300);
      const r=await p.evaluate(async()=>{const W=z=>new Promise(r=>setTimeout(r,z));document.getElementById("fListen").click();await W(1500);const on=__smk.P().on;
        document.getElementById("pStop").click();await W(300);__smk.openAyah(__smk.SST[2]+3);await W(700);const m=document.getElementById("aMark");
        m.click();await W(100);m.click();await W(100);const removed=m.getAttribute("aria-pressed")==="false";const u=document.querySelector("#toast .tundo");u&&u.click();await W(200);
        return {on,removed,undo:!!u,back:m.getAttribute("aria-pressed")==="true"}});
      ok(r.on,`${name}: "استمع" starts the recitation`);ok(r.removed&&r.undo&&r.back,`${name}: undo puts a removed mark back ${JSON.stringify(r)}`);
      await p.evaluate(()=>{document.getElementById("aMark").click()});await p.keyboard.press("Escape");await p.waitForTimeout(500)}
    /* reciting from memory: the Android plugin's words reveal the hidden words in order */
    {await p.keyboard.press("Escape");await p.evaluate(()=>{document.getElementById("app").classList.remove("calm");document.getElementById("fPrefs").click()});await p.waitForTimeout(400);
      await p.evaluate(()=>{const b=document.getElementById("bFlow");if(b.getAttribute("aria-pressed")==="true")b.click()});await p.waitForTimeout(300);
      await p.evaluate(()=>document.getElementById("bMemo").click());await p.waitForTimeout(900);
      const r=await p.evaluate(async()=>{const nx=()=>document.querySelector("#book .pg .nx");const a=nx();if(!a)return {err:"no cursor"};
        document.getElementById("mMic").click();await new Promise(z=>setTimeout(z,200));
        const t=a.textContent+" "+(a.nextElementSibling&&a.nextElementSibling.nextElementSibling?a.nextElementSibling.nextElementSibling.textContent:"");
        window.__L.Speechheard&&window.__L.Speechheard({text:t,final:true});await new Promise(z=>setTimeout(z,300));
        return {on:document.getElementById("mMic").classList.contains("on"),moved:nx()!==a,shown:!a.classList.contains("hid")}});
      ok(r.on&&r.moved&&r.shown,`${name}: reciting by voice reveals the words heard ${JSON.stringify(r)}`)}
    /* the recogniser's plain spelling, a lost first word and the ayah-end marker in between still carry the
       cursor through two whole ayat, without marking anything wrong */
    {const r=await p.evaluate(async()=>{const nx=()=>document.querySelector("#book .pg .nx");const a0=nx();if(!a0)return {err:"no cursor"};
        const id=+a0.dataset.a,pg=a0.closest(".pg");const plain=t=>t.replace(/[\u064B-\u065F\u06D6-\u06ED\u08F0-\u08F2\u0640]/g,"").replace(/\u0670/g,"ا").replace(/ٱ/g,"ا");
        const ws=[...pg.querySelectorAll(".lb [data-a][data-w]")].filter(w=>+w.dataset.a===id||+w.dataset.a===id+1).map(w=>plain(w.textContent));
        window.__L.Speechheard({text:ws.slice(1).join(" "),final:true,alts:["كلام لا علاقة له",ws.slice(1).join(" ")]});await new Promise(z=>setTimeout(z,300));
        const n=nx();return {from:id,to:n?+n.dataset.a:0,red:document.querySelectorAll("#book .pg .err").length}});
      ok(r.to>r.from+1&&!r.red,`${name}: voice follows plain spelling past a lost word and an ayah end ${JSON.stringify(r)}`)}
    /* follow-along recitation: the text stays on the page, recited words take the colour, the word due sits in the
       pill, and reciting the whole page turns to the next one */
    {await p.keyboard.press("Escape");await p.waitForTimeout(400);
      await p.evaluate(()=>{document.getElementById("app").classList.remove("calm");document.getElementById("fPrefs").click()});await p.waitForTimeout(400);
      await p.evaluate(()=>document.getElementById("bFollow").click());await p.waitForTimeout(900);
      const r=await p.evaluate(async()=>{const W=z=>new Promise(r=>setTimeout(r,z)),app=document.getElementById("app"),c0=+localStorage.getItem("mushaf.last");
        const nx=()=>document.querySelector("#book .pg .nx");const a=nx();if(!a)return {err:"no cursor"};
        const faint=getComputedStyle(a.nextElementSibling.nextElementSibling).color,pg=a.closest(".pg");
        const plain=t=>t.replace(/[\u064B-\u065F\u06D6-\u06ED\u08F0-\u08F2\u0640]/g,"").replace(/\u0670/g,"ا").replace(/ٱ/g,"ا");
        const all=[...pg.querySelectorAll(".lb [data-a][data-w]")].map(w=>plain(w.textContent));
        window.__L.Speechheard({text:all.slice(0,6).join(" "),final:true});await W(300);
        const moved=nx()!==a,col=getComputedStyle(a).color;
        /* the word being said lights up from the partial text, before the phrase ends; the pill glides to it */
        const b0=nx();window.__L.Speechheard({text:plain(b0.textContent),final:false});await W(150);
        const instant=nx()!==b0&&!b0.classList.contains("hid"),pill=document.getElementById("flPill"),pillOn=!!pill&&!pill.hidden&&pill.parentNode===nx().closest(".pg");
        window.__L.Speechheard({text:plain(b0.textContent),final:true});await W(100);
        for(let i=6;i<all.length;i+=8){window.__L.Speechheard({text:all.slice(i,i+8).join(" "),final:true});await W(60)}
        /* a spread: the second page follows the first */
        await W(200);{const n2=nx(),p2=n2&&n2.closest(".pg");if(p2&&p2!==pg&&+localStorage.getItem("mushaf.last")===c0){const ws=[...p2.querySelectorAll(".lb [data-a][data-w]")].map(w=>plain(w.textContent));
          for(let i=0;i<ws.length;i+=8){window.__L.Speechheard({text:ws.slice(i,i+8).join(" "),final:true});await W(60)}}}
        await W(2600);
        return {on:app.classList.contains("follow")&&document.getElementById("mMic").classList.contains("on"),faint,col,moved,instant,pillOn,from:c0,to:+localStorage.getItem("mushaf.last"),still:app.classList.contains("follow")}});
      ok(r.instant&&r.pillOn,`${name}: follow-along lights the word as it is said and the pill moves to the next ${JSON.stringify(r)}`);
      ok(r.on&&r.moved&&r.faint!=="rgba(0, 0, 0, 0)"&&r.col!==r.faint,`${name}: follow-along shows the text and colours the words recited ${JSON.stringify(r)}`);
      ok(r.to>r.from&&r.still,`${name}: reciting the whole page turns to the next and keeps following ${JSON.stringify(r)}`);
      await p.keyboard.press("Escape");await p.waitForTimeout(400);
      ok(await p.evaluate(()=>!document.getElementById("app").classList.contains("follow")&&!document.querySelector("#book .hid")),`${name}: leaving follow-along clears the page`)}
    /* the app's own Quran recogniser: bare Uthmani letters, a lit word from the partial, whole words in each final */
    {await p.evaluate(()=>{document.getElementById("app").classList.remove("calm");document.getElementById("fPrefs").click()});await p.waitForTimeout(400);
      await p.evaluate(()=>document.getElementById("bFollow").click());await p.waitForTimeout(900);
      const r=await p.evaluate(async()=>{const W=z=>new Promise(r=>setTimeout(r,z)),nx=()=>{const l=+localStorage.getItem("mushaf.last");return document.querySelector(`#book .pg[data-p="${l}"] .nx,#book .pg[data-p="${l+1}"] .nx`)};
        const F={"أ":"ا","إ":"ا","آ":"ا","ٱ":"ا","ى":"ي","ئ":"ي","ؤ":"و","ة":"ه"},L="ءابتثجحخدذرزسشصضطظعغفقكلمنهوي";
        const bare=t=>[...t].map(c=>F[c]||c).filter(c=>L.includes(c)).join("");
        const a=nx();if(!a)return {err:"no cursor"};const pg=a.closest(".pg"),ws=[...pg.querySelectorAll(".lb [data-a][data-w]")];const i0=ws.indexOf(a);
        window.__L.Speechheard({text:bare(ws[i0].textContent),final:false,engine:"q"});await W(150);const lit=!ws[i0].classList.contains("hid");
        window.__L.Speechheard({text:ws.slice(i0,i0+5).map(w=>bare(w.textContent)).join(" "),final:true,engine:"q"});await W(200);
        return {lit,moved:ws.indexOf(nx())-i0,red:document.querySelectorAll("#book .pg .err").length,sample:bare(ws[i0+1].textContent)}});
      ok(r.lit&&r.moved===5&&!r.red,`${name}: the Quran recogniser's letters follow the page word by word ${JSON.stringify(r)}`);
      await p.keyboard.press("Escape");await p.waitForTimeout(400)}
    if(name==="phone"){/* prayer times are computed on the device: Baghdad, Ja'fari, a fixed day, in Baghdad time */
      const r=await p.evaluate(()=>{const c=new adhan.Coordinates(33.3152,44.3661),d=new Date(Date.UTC(2026,10,4,9));
        const f=t=>["fajr","sunrise","dhuhr","asr","maghrib","isha"].map(k=>t[k].toLocaleTimeString("en-GB",{timeZone:"Asia/Baghdad",hour:"2-digit",minute:"2-digit"}));
        return {m:f(new adhan.PrayerTimes(c,d,adhan.CalculationMethod.MuslimWorldLeague()))}});
      const inOrder=a=>a.every((x,i)=>!i||x>a[i-1]);
      ok(inOrder(r.m)&&r.m[0]>="04:45"&&r.m[0]<="05:15"&&r.m[4]>="16:55"&&r.m[4]<="17:20",`${name}: prayer times for Baghdad look right ${JSON.stringify(r)}`)}
    {/* المتشابهات: the switch colours the twin phrases on the open page (page 51, built next to the open page 50, holds 3:10 and 3:11 from the huffaz' list) and takes them off again */
      await p.evaluate(()=>document.getElementById("bMut").click());await p.waitForTimeout(1800);
      const on=await p.evaluate(()=>[...new Set([...document.querySelectorAll('.pg[data-p="51"] .ms')].map(w=>+w.dataset.a))]);
      await p.evaluate(()=>document.getElementById("bMut").click());await p.waitForTimeout(800);
      const off=await p.evaluate(()=>document.querySelectorAll(".pg .ms,.pg .md,#flBody .ms,#flBody .md").length);
      ok(on.length>=2&&!off,`${name}: similar phrases coloured on the page and cleared when switched off (ayat ${on.join(",")}; ${off} left)`)}
    await ctx.close()}
  /* saved data from an old version or an edited backup file (wrong shapes, a name with markup in it) must not
     break the page or run as code */
  {const ctx=await b.newContext({viewport:{width:412,height:891},hasTouch:true,isMobile:true});const p=await ctx.newPage();const errs=[];p.on("pageerror",e=>errs.push(e.message));
    await p.addInitScript(()=>{const X='"><img src=x onerror="window.__x=1">';const S=(k,v)=>localStorage.setItem(k,v);
      S("mushaf.last","2");S("mushaf.hintN","9");S("mushaf.easyQ","1");S("mushaf.marks",'[1,"x",null,{}]');S("mushaf.notes",'{"1":"x","2":{"t":5},"3":null}');S("mushaf.memo",'{"1":null,"2":5}');S("mushaf.recent","{}");S("mushaf.khatma","true");S("mushaf.az","null");
      S("mushaf.hl",JSON.stringify({"10":{k:"y"},"11":null,"12":5}));S("mushaf.hlNames",JSON.stringify({y:X}));S("mushaf.mgSet",JSON.stringify([{k:"a",c:"#e2b93b",n:"عام"},{k:"b",c:"#5fae7d",n:X}]))});
    await p.goto(`http://localhost:${port}/index.html`);await p.waitForTimeout(3000);
    const w=await p.$('#book .pg [data-a="10"]');const bb=w&&await w.boundingBox();
    if(bb){await p.mouse.move(bb.x+bb.width/2,bb.y+bb.height/2);await p.mouse.down();await p.waitForTimeout(700);await p.mouse.up();await p.waitForTimeout(800);
      await p.evaluate(()=>{const h=document.getElementById("aHl");h&&h.click()});await p.waitForTimeout(600)}
    await p.evaluate(()=>{document.getElementById("bIndex").click()});await p.waitForTimeout(400);for(const t of ["r","c","m"]){await p.evaluate(t=>document.getElementById("t-"+t).click(),t);await p.waitForTimeout(400)}
    const r=await p.evaluate(()=>({lines:document.querySelectorAll("#book .pg .lb").length,x:window.__x||0}));
    ok(r.lines>0&&!errs.length,`bad saved data: the page still opens (${r.lines} lines) ${errs.join(" | ")}`);ok(!r.x,"bad saved data: a colour name with markup stays text");
    await ctx.close()}
  /* page look on launch: gold ayah numbers must not stop a big-text reader getting the medium stroke */
  {const ctx=await b.newContext({viewport:{width:412,height:891},hasTouch:true,isMobile:true});const p=await ctx.newPage();
    await p.addInitScript(()=>{const S=(k,v)=>localStorage.setItem(k,v);S("mushaf.last","2");S("mushaf.hintN","9");S("mushaf.easyQ","1");S("mushaf.uz","1.3");S("mushaf.an","1")});
    await p.goto(`http://localhost:${port}/index.html`);await p.waitForTimeout(2500);
    const r=await p.evaluate(()=>({wt:document.documentElement.dataset.wt||"",an:document.documentElement.dataset.an||""}));
    ok(r.wt==="1"&&r.an==="1",`page look: big text keeps the medium stroke with gold ayah numbers on (${JSON.stringify(r)})`);
    await ctx.close()}
  /* the half-open (tabletop) view on a phone: nothing wider than the screen (a grid column once grew to the bar's
     own width, so the page, tafsir and buttons ran off both sides) */
  {const ctx=await b.newContext({viewport:{width:412,height:891},hasTouch:true,isMobile:true});const p=await ctx.newPage();
    await p.addInitScript(()=>{localStorage.setItem("mushaf.last","439");localStorage.setItem("mushaf.hintN","9");localStorage.setItem("mushaf.easyQ","1");localStorage.setItem("mushaf.table","1")});
    await p.goto(`http://localhost:${port}/index.html`);await p.waitForTimeout(3000);
    const r=await p.evaluate(()=>({sw:document.documentElement.scrollWidth,w:innerWidth,table:document.getElementById("app").classList.contains("table"),deck:document.querySelectorAll("#deckList .dk").length}));
    ok(r.table&&r.deck>0&&r.sw<=412&&r.w===412,`phone half open: page and tafsir fit the screen (${JSON.stringify(r)})`);
    /* the page takes the full width there, and a finger drawn up shows its lower half; a swipe turns and starts at the top */
    {const pr=()=>p.evaluate(()=>{const q=document.getElementById("book").getBoundingClientRect();return {w:Math.round(q.width),top:Math.round(q.top),meta:document.getElementById("wMeta").textContent}});
      const st=await p.locator("#stage").boundingBox(),drag=async(x0,y0,x1,y1)=>{await p.mouse.move(x0,y0);await p.mouse.down();for(let k=1;k<=10;k++){await p.mouse.move(x0+(x1-x0)*k/10,y0+(y1-y0)*k/10);await p.waitForTimeout(16)}await p.mouse.up();await p.waitForTimeout(800)};
      const a=await pr();await drag(200,st.y+st.height*.8,204,st.y+st.height*.1);const b2=await pr();await drag(80,st.y+st.height*.5,330,st.y+st.height*.52);await p.waitForTimeout(600);const c=await pr();
      ok(a.w>=380&&b2.top<a.top-100&&c.top===a.top&&c.meta!==a.meta,`phone half open: full-width page slides up to show the rest, a swipe turns to the next page's top (${JSON.stringify([a,b2,c])})`)}
    /* the settings menu drops from the bar half way down and must end on screen (it ran past the bottom) */
    /* on a real Android phone the status bar pushes the whole app down (it did on Mn's phone, cutting off إغلاق) */
    {await p.evaluate(()=>{const r=document.documentElement;r.classList.add("android");r.style.setProperty("--cutT","42px");r.style.setProperty("--cutB","24px");dispatchEvent(new Event("resize"))});await p.waitForTimeout(700);
      await p.click("#bPrefs");await p.waitForTimeout(600);const r=await p.evaluate(()=>{const q=document.getElementById("prefs").getBoundingClientRect(),x=document.getElementById("prefsX").getBoundingClientRect();return {top:Math.round(q.top),bottom:Math.round(Math.max(q.bottom,x.bottom)),h:innerHeight-24}});
      ok(r.top>300&&r.bottom<=r.h,`phone half open: settings menu ends on screen (${JSON.stringify(r)})`)}
    await ctx.close()}
  /* Android: at a surah's end the native player goes straight on to the next surah's file, queued in advance
     (it used to stop there with the screen off, and the app slept). A fake native player stands in for ExoPlayer. */
  {const ctx=await b.newContext({viewport:{width:412,height:891},hasTouch:true,isMobile:true});const p=await ctx.newPage();
    await p.addInitScript(()=>{localStorage.setItem("mushaf.hintN","9");localStorage.setItem("mushaf.easyQ","1");localStorage.setItem("mushaf.last","604");localStorage.setItem("mushaf.playLast","6225");localStorage.setItem("mushaf.reciter","husary");
  const L={},log=window.__log=[];const emit=(ev,d)=>(L["Audio:"+ev]||[]).forEach(f=>f(d||{}));
  /* fake ExoPlayer: surah durations from the timing file end + 400 ms */
  const X={list:[],i:0,pos:0,playing:false,t:0,dur:u=>{const s=+String(u).match(/(\d{3})\.mp3/)[1];const rows=window.__aud&&window.__aud.surahs[s];return rows?rows[rows.length-1][2]+400:60000}};
  setInterval(()=>{if(!X.playing||!X.list.length)return;X.pos+=200;if(X.pos>=X.dur(X.list[X.i])){if(X.i+1<X.list.length){X.i++;X.pos=0;log.push("AUTO->"+X.list[X.i].slice(-7));emit("advanced");}else{X.playing=false;log.push("ENDED");emit("time",{ms:X.pos,playing:false});emit("ended");return}}emit("time",{ms:X.pos,playing:X.playing})},200);
  window.Capacitor={isNativePlatform:()=>true,getPlatform:()=>"android",isPluginAvailable:()=>true,convertFileSrc:u=>u,
    addListener:(pl,ev,fn)=>{(L[pl+":"+ev]=L[pl+":"+ev]||[]).push(fn);return {remove(){}}},
    nativePromise:(pl,m,o)=>{if(pl==="Audio"){log.push(m+(o&&o.url!=null?" "+String(o.url).slice(-7):"")+(o&&o.ms!=null?" @"+o.ms:""));
        if(m==="load"){X.list=[o.url];X.i=0;X.pos=o.ms||0;setTimeout(()=>emit("loaded",{dur:1}),50)}
        if(m==="queue"){X.list=X.list.slice(0,X.i+1);if(o.url)X.list.push(o.url)}
        if(m==="play")X.playing=true;if(m==="pause")X.playing=false;if(m==="seek")X.pos=o.ms}
      return Promise.resolve(pl==="Playback"&&m==="insets"?null:{})}};
  fetch("audio/husary.json").then(r=>r.json()).then(d=>window.__aud=d)});
await p.goto(`http://localhost:${port}/index.html`);await p.waitForTimeout(3000);
    await p.evaluate(()=>document.getElementById("fListen").click());await p.waitForTimeout(10500);
    const r=await p.evaluate(()=>({t:document.getElementById("pTitle").textContent,log:window.__log.join(" | ")}));
    ok(/الفلق/.test(r.t)&&/queue 113\.mp3/.test(r.log)&&/AUTO->113/.test(r.log)&&!/load 113/.test(r.log),`android: the next surah follows the last ayah with no stop (${JSON.stringify(r)})`);
    await ctx.close()}
  await b.close();srv.close();fs.unlinkSync(path.join(WWW,"smoke.html"));
  if(fails.length){console.log(`\n${fails.length} check(s) failed`);process.exit(1)}console.log("\nall checks passed")})().catch(e=>{console.error(e);srv.close();process.exit(1)});
