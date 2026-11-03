/* Smoke test run on every push before the APK is built. It catches the kind of mistake that reached a
   test build once: recitation that stops right after it starts. Run: node tests/smoke.js (after npm run build) */
const {chromium}=require("playwright");const fs=require("fs"),path=require("path"),http=require("http");
const WWW=path.join(__dirname,"..","www"),TONE=fs.readFileSync(path.join(__dirname,"tone.mp3"));
const src=fs.readFileSync(path.join(WWW,"index.html"),"utf8"),hook='recUI();audData(reciter).then(d=>{if(!aud)aud=d});';
if(!src.includes(hook))throw new Error("test hook point not found in www/index.html");
fs.writeFileSync(path.join(WWW,"smoke.html"),src.replace(hook,hook+"window.__smk={playFrom,P:()=>player,AU,setPlaying,SST,openAyah};"));
const types={".html":"text/html",".js":"text/javascript",".json":"application/json",".woff2":"font/woff2",".ttf":"font/ttf",".css":"text/css"};
const srv=http.createServer((q,r)=>{const f=path.join(WWW,decodeURIComponent(q.url.split("?")[0]));
  fs.readFile(f,(e,b)=>{if(e){r.writeHead(404);r.end();return}r.writeHead(200,{"content-type":types[path.extname(f)]||"application/octet-stream"});r.end(b)})}).listen(0);
const fails=[];const ok=(c,m)=>{console.log((c?"ok   ":"FAIL ")+m);if(!c)fails.push(m)};
(async()=>{const port=srv.address().port;
  const b=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||undefined,args:["--autoplay-policy=no-user-gesture-required"]});
  for(const [name,vp] of [["phone",{width:412,height:891}],["fold",{width:1000,height:760}]]){
    const ctx=await b.newContext({viewport:vp,hasTouch:true,isMobile:true});const p=await ctx.newPage();const errs=[];
    p.on("pageerror",e=>errs.push(e.message));
    await p.addInitScript(()=>{localStorage.setItem("mushaf.last","50");localStorage.setItem("mushaf.hintN","9");localStorage.setItem("mushaf.easyQ","1");window.__L={};
      window.Capacitor={isNativePlatform:()=>true,getPlatform:()=>"android",convertFileSrc:u=>u,addListener:(pl,ev,fn)=>{window.__L[pl+ev]=fn},
        nativePromise:(pl,m,o)=>m==="getUri"?Promise.resolve({uri:"file:///d/"+o.path}):Promise.resolve({})}});
    await p.route(/\.mp3(\?|$)/,r=>{const h=r.request().headers().range,B=TONE;if(h){const m=/bytes=(\d+)-(\d*)/.exec(h),a=+m[1],e=m[2]?+m[2]:B.length-1;
      return r.fulfill({status:206,headers:{"content-type":"audio/mpeg","accept-ranges":"bytes","content-range":`bytes ${a}-${e}/${B.length}`},body:B.subarray(a,e+1)})}
      r.fulfill({status:200,headers:{"content-type":"audio/mpeg","accept-ranges":"bytes"},body:B})});
    await p.goto(`http://localhost:${port}/smoke.html`);await p.waitForTimeout(4000);
    ok(!errs.length,`${name}: no script errors on load ${errs.join(" | ")}`);
    const lock=await p.evaluate(()=>document.getElementById("qLockMsg").textContent);ok(/مطابق/.test(lock),`${name}: Quran text lock matches`);
    await p.evaluate(()=>__smk.playFrom(__smk.SST[2]+3));await p.waitForTimeout(3000);
    let s=await p.evaluate(()=>({on:__smk.P().on,paused:__smk.AU.paused,t:__smk.AU.currentTime}));
    ok(s.on&&!s.paused,`${name}: recitation keeps playing 3s after start (on=${s.on} paused=${s.paused})`);
    const t1=s.t;await p.waitForTimeout(1200);s=await p.evaluate(()=>({t:__smk.AU.currentTime}));ok(s.t>t1+.5,`${name}: audio time moves (${t1.toFixed(1)} → ${s.t.toFixed(1)})`);
    await p.evaluate(()=>window.__L.Playbackaction&&window.__L.Playbackaction({a:"pause"}));await p.waitForTimeout(300);
    ok(await p.evaluate(()=>__smk.AU.paused&&!__smk.P().on),`${name}: notification pause pauses`);
    await p.evaluate(()=>window.__L.Playbackaction&&window.__L.Playbackaction({a:"play"}));await p.waitForTimeout(800);
    ok(await p.evaluate(()=>!__smk.AU.paused&&__smk.P().on),`${name}: notification play resumes`);
    await p.evaluate(()=>document.getElementById("pStop").click());await p.waitForTimeout(300);
    
    await p.evaluate(()=>{document.getElementById("app").classList.remove("calm");document.getElementById("fPrefs").click()});await p.waitForTimeout(500);
    for(const z of ["1","1.3"]){await p.evaluate(z=>document.querySelector(`[data-uz="${z}"]`).click(),z);await p.waitForTimeout(400);
      const r=await p.evaluate(()=>{const r=document.getElementById("prefs").getBoundingClientRect();return [r.top,r.bottom]});
      ok(r[0]>=-1&&r[1]<=vp.height+1,`${name}: settings panel on screen at text size ${z} (${r.map(Math.round)})`)}
    await ctx.close()}
  await b.close();srv.close();fs.unlinkSync(path.join(WWW,"smoke.html"));
  if(fails.length){console.log(`\n${fails.length} check(s) failed`);process.exit(1)}console.log("\nall checks passed")})().catch(e=>{console.error(e);srv.close();process.exit(1)});
