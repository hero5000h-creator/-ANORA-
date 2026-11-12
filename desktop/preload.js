/* The page talks to the Windows side through this small bridge only. app.html turns it into the same
   window.Capacitor interface the Android build has, so the app code stays one file for every platform. */
"use strict";
const {contextBridge,ipcRenderer}=require("electron");
const L=[];
ipcRenderer.on("cap-ev",(e,pl,ev,data)=>{for(const x of L)if(x.pl===pl&&x.ev===ev)try{x.fn(data)}catch(err){}});
contextBridge.exposeInMainWorld("__anora",{
  os:process.platform,
  /* options travel as JSON text, so dates and nested objects arrive exactly as the page wrote them */
  invoke:(pl,m,json)=>ipcRenderer.invoke("cap",String(pl),String(m),typeof json==="string"?json:"{}"),
  on:(pl,ev,fn)=>{if(typeof fn==="function")L.push({pl:String(pl),ev:String(ev),fn})}});
