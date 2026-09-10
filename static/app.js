  const NSW_API_KEY = "1MYSRAx5yvqHUZc6VGtxix6oMA2qgfRT";
  const NSW_AUTH    = "Basic MU1ZU1JBeDV5dnFIVVpjNlZHdHhpeDZvTUEycWdmUlQ6Qk12V2FjdzE1RXQ4dUZHRg==";
  const TOMTOM_KEY  = "RoiDwi5Y35NaVKTJEyFTX5VtED45vS2e";

  let tripMode='one', filterMode='all', searchMode='near', routeType='fastest', priority='savings';
  let userLat=null, userLon=null;

  // Cache: keyed by routeType → {results, baseDist, routeKm, routeMins}
  const cache={};
  let activeRouteType='fastest';

  // ── Math helpers ──────────────────────────────────────────────────────────────
  function haversineRaw(lat1,lon1,lat2,lon2){
    const R=6371,dLat=(lat2-lat1)*Math.PI/180,dLon=(lon2-lon1)*Math.PI/180;
    const a=Math.sin(dLat/2)**2+Math.cos(lat1*Math.PI/180)*Math.cos(lat2*Math.PI/180)*Math.sin(dLon/2)**2;
    return R*2*Math.atan2(Math.sqrt(a),Math.sqrt(1-a));
  }
  function haversine(a,b,c,d){return haversineRaw(a,b,c,d)*1.28;}

  // ── TomTom ────────────────────────────────────────────────────────────────────
  const ttRouteTypeMap={fastest:'fastest',shortest:'shortest',eco:'eco'};

  async function tomtomRoute(olat,olon,dlat,dlon,rtype){
    const tt=ttRouteTypeMap[rtype]||'fastest';
    const url=`https://api.tomtom.com/routing/1/calculateRoute/${olat},${olon}:${dlat},${dlon}/json?key=${TOMTOM_KEY}&travelMode=car&routeType=${tt}`;
    const r=await fetch(url,{signal:AbortSignal.timeout(10000)});
    if(!r.ok) throw new Error('TomTom '+r.status);
    const d=await r.json();
    const route=d.routes[0];
    const distKm=route.summary.lengthInMeters/1000;
    const mins=Math.round(route.summary.travelTimeInSeconds/60);
    const points=[];
    for(const leg of route.legs) for(const pt of leg.points) points.push([pt.latitude,pt.longitude]);
    return{distKm,mins,points};
  }

  async function tomtomDist(olat,olon,dlat,dlon){
    try{
      const url=`https://api.tomtom.com/routing/1/calculateRoute/${olat},${olon}:${dlat},${dlon}/json?key=${TOMTOM_KEY}&travelMode=car&routeType=fastest`;
      const r=await fetch(url,{signal:AbortSignal.timeout(8000)});
      if(!r.ok) throw new Error();
      const d=await r.json();
      return d.routes[0].summary.lengthInMeters/1000;
    }catch{return haversine(olat,olon,dlat,dlon);}
  }

  // ── Polyline helpers ──────────────────────────────────────────────────────────
  function samplePolyline(points,stepKm){
    if(!points.length)return[];
    const s=[points[0]];let acc=0;
    for(let i=1;i<points.length;i++){
      acc+=haversineRaw(points[i-1][0],points[i-1][1],points[i][0],points[i][1]);
      if(acc>=stepKm){s.push(points[i]);acc=0;}
    }
    const last=points[points.length-1];
    if(s[s.length-1]!==last)s.push(last);
    return s;
  }
  function ptToSegDist(plat,plon,alat,alon,blat,blon){
    const dx=blon-alon,dy=blat-alat,lenSq=dx*dx+dy*dy;
    if(!lenSq)return haversineRaw(plat,plon,alat,alon);
    let t=Math.max(0,Math.min(1,((plon-alon)*dx+(plat-alat)*dy)/lenSq));
    return haversineRaw(plat,plon,alat+t*dy,alon+t*dx);
  }
  function distToPolyline(lat,lon,pts){
    let m=Infinity;
    for(let i=1;i<pts.length;i++) m=Math.min(m,ptToSegDist(lat,lon,pts[i-1][0],pts[i-1][1],pts[i][0],pts[i][1]));
    return m;
  }

  // ── Nominatim ─────────────────────────────────────────────────────────────────
  async function geocode(q){
    const r=await fetch(`https://nominatim.openstreetmap.org/search?q=${encodeURIComponent(q)}&format=json&limit=1&countrycodes=au`,{headers:{'User-Agent':'FuelOptimizer/2.0'}});
    const d=await r.json();
    if(!d.length)throw new Error('Could not geocode: '+q);
    return[parseFloat(d[0].lat),parseFloat(d[0].lon)];
  }

  // ── NSW FuelCheck ─────────────────────────────────────────────────────────────
  async function getNSWToken(){
    const r=await fetch('https://api.onegov.nsw.gov.au/oauth/client_credential/accesstoken?grant_type=client_credentials',{headers:{Authorization:NSW_AUTH}});
    if(!r.ok)throw new Error('NSW auth failed: '+r.status);
    return(await r.json()).access_token;
  }
  async function fetchStations(lat,lon,radiusKm,fuelType,token){
    const r=await fetch('https://api.onegov.nsw.gov.au/FuelPriceCheck/v1/fuel/prices/nearby',{
      method:'POST',
      headers:{Authorization:`Bearer ${token}`,apikey:NSW_API_KEY,'Content-Type':'application/json; charset=utf-8',transactionid:'1',requesttimestamp:'01/01/2024 00:00:00 AM'},
      body:JSON.stringify({FuelType:fuelType,Latitude:lat,Longitude:lon,Radius:radiusKm,SortBy:'Price'})
    });
    if(!r.ok)throw new Error('NSW FuelCheck: '+r.status);
    return r.json();
  }
  function parseStations(raw){
    const pl={};
    (raw.prices||[]).forEach(p=>{if(p.price!=null)pl[p.stationcode]=parseFloat(p.price)/100;});
    // Stations with no reported coordinates used to fall back to the search center
    // (your location, or a corridor sample point) - that made them look like a ~0km
    // detour and let them pass every corridor-width check regardless of where they
    // actually are, potentially crowding out a real, closer, cheaper station. Safer
    // to drop them: a station whose location isn't known can't be ranked by distance.
    return(raw.stations||[]).filter(s=>pl[s.code]!=null && s.location?.latitude!=null && s.location?.longitude!=null).map(s=>({
      name:s.name||'Unknown',brand:s.brand||'',price:pl[s.code],
      lat:parseFloat(s.location.latitude),lon:parseFloat(s.location.longitude),
      address:s.address||''
    }));
  }

  // ── GPS ───────────────────────────────────────────────────────────────────────
  // Returns a Promise that ALWAYS resolves once userLat/userLon are set, one
  // way or another (real GPS, or the Sydney CBD fallback below) - never
  // rejects, so both the header's manual "Detect Location" button and
  // runSearch()'s own automatic call (the single-button flow doesn't
  // require a separate manual tap first) can simply await/chain it without
  // duplicating the same fallback handling in two places.
  function detectLocation(){
    document.getElementById('loc-btn-txt').textContent='Locating…';
    return new Promise(resolve=>{
      if(!navigator.geolocation){
        showErr('Geolocation not supported — using Sydney CBD.');
        userLat=-33.8688;userLon=151.2093;resolve();return;
      }
      navigator.geolocation.getCurrentPosition(
        pos=>{userLat=pos.coords.latitude;userLon=pos.coords.longitude;
          document.getElementById('loc-btn-txt').textContent=`${userLat.toFixed(4)}, ${userLon.toFixed(4)}`;
          document.getElementById('loc-label').textContent='📍 GPS location active';
          resolve();},
        ()=>{document.getElementById('loc-btn-txt').textContent='Location denied';
          showWarn('Location access denied — using Sydney CBD.');
          userLat=-33.8688;userLon=151.2093;resolve();}
      );
    });
  }

  // ── UI state ──────────────────────────────────────────────────────────────────
  function updateGauge(){
    const v=document.getElementById('gauge').value;
    document.getElementById('gauge-fill').style.width=v+'%';
    document.getElementById('gauge-label').textContent=v+'%';
    updateSummary();
  }
  function updateSummary(){
    const tank=parseFloat(document.getElementById('tank').value)||60;
    const pct=parseFloat(document.getElementById('gauge').value)||25;
    const spendAmt=parseFloat(document.getElementById('manual-spend').value)||0;
    const ft=document.getElementById('fueltype').value;
    if(spendAmt>0){
      document.getElementById('fill-l').textContent='$'+spendAmt.toFixed(0)+' worth';
    } else {
      const fill=(tank*(1-pct/100)).toFixed(1);
      document.getElementById('fill-l').textContent=fill+' L';
    }
    document.getElementById('fill-ft').textContent=ft;
  }
  function setTrip(m){
    tripMode=m;
    document.getElementById('tog-one').classList.toggle('on',m==='one');
    document.getElementById('tog-ret').classList.toggle('on',m==='return');
  }
  function setMode(m){
    searchMode=m;
    document.getElementById('mode-near').classList.toggle('on',m==='near');
    document.getElementById('mode-corridor').classList.toggle('on',m==='corridor');
    document.getElementById('near-opts').style.display=m==='near'?'block':'none';
    document.getElementById('corridor-opts').classList.toggle('show',m==='corridor');
  }
  function setRouteType(rt){
    routeType=rt;
    ['fastest','shortest','eco'].forEach(t=>document.getElementById('rt-'+t).classList.toggle('on',t===rt));
  }
  function setPriority(p){
    priority=p;
    document.getElementById('pri-savings').classList.toggle('on',p==='savings');
    document.getElementById('pri-route').classList.toggle('on',p==='route');
  }
  function setFilter(m){
    filterMode=m;
    document.getElementById('chip-all').classList.toggle('on',m==='all');
    document.getElementById('chip-rt').classList.toggle('on',m==='route');
    applyFilters();
  }
  function applyFilters(){
    const maxD=parseInt(document.getElementById('detour-f').value);
    document.getElementById('detour-v').textContent=maxD>=20?'any':maxD+'km';
    document.querySelectorAll('.scard').forEach(c=>{
      const detour=parseFloat(c.dataset.detour);
      let show=true;
      if(filterMode==='route'&&detour>3)show=false;
      if(maxD<20&&detour>maxD)show=false;
      c.classList.toggle('hidden',!show);
    });
  }
  function routeBadge(d){return d<=3?'✅':d<=8?'🔶':'❌';}
  function showErr(msg){const b=document.getElementById('error-box');b.innerHTML='⚠ '+msg;b.style.display='block';}
  function showWarn(msg){const b=document.getElementById('warn-box');b.innerHTML='ℹ '+msg;b.style.display='block';}
  function clearMessages(){document.getElementById('error-box').style.display='none';document.getElementById('warn-box').style.display='none';}
  function setBtn(html){document.getElementById('cta-btn').innerHTML=html;}
  function setProgress(pct){
    const bar=document.getElementById('prog-bar');
    bar.style.display=pct>0&&pct<100?'block':'none';
    document.getElementById('prog-fill').style.width=pct+'%';
  }

  // ── Sort results by priority ──────────────────────────────────────────────────
  function sortResults(results){
    if(priority==='route'){
      // Weighted score: penalise detour heavily (each extra km = $1 penalty equivalent)
      return [...results].sort((a,b)=>(a.total+a.detour*1.2)-(b.total+b.detour*1.2));
    }
    return [...results].sort((a,b)=>a.total-b.total);
  }

  // ── Route tab UI ──────────────────────────────────────────────────────────────
  const routeLabels={fastest:'⚡ Fastest',shortest:'📏 Shortest',eco:'🌿 Eco'};

  function renderRouteTabs(activeRt){
    const container=document.getElementById('route-tabs');
    container.innerHTML='';
    ['fastest','shortest','eco'].forEach(rt=>{
      const c=cache[rt];
      const tab=document.createElement('div');
      tab.className='rtab'+(rt===activeRt?' active':'')+(c?' cached':'');
      tab.onclick=()=>switchRouteTab(rt);
      tab.innerHTML=`
        <div class="rtab-label">${routeLabels[rt]}</div>
        ${c
          ? `<div class="rtab-dist">${c.routeKm.toFixed(1)} km · ${c.routeMins} min</div>
             <div class="rtab-stations">${c.results.length} stations</div>
             <div class="rtab-best">Best $${c.results[0]?.total.toFixed(2)||'—'}</div>`
          : `<div class="rtab-dist" style="color:#cbd5e1">${rt===activeRt?'searching…':'not searched'}</div>`
        }`;
      container.appendChild(tab);
    });
  }

  function switchRouteTab(rt){
    if(!cache[rt]){
      // Not yet searched — trigger a search for this route type
      routeType=rt;
      setRouteType(rt);
      runSearch();
      return;
    }
    activeRouteType=rt;
    renderRouteTabs(rt);
    renderStationList(cache[rt].results, cache[rt].baseDist, rt, true);
  }

  // ── Render station list ───────────────────────────────────────────────────────
  // hasDest: false when the driver searched with no destination set (Near Me
  // only) - hides destination/route-specific fields (to-dest distance,
  // detour badge, corridor badge) that have no meaning without a route.
  function renderStationList(rawResults, baseDist, rt, hasDest){
    const sorted=sortResults(rawResults);
    const worst=sorted[sorted.length-1]?.total||0;
    sorted.forEach(r=>r.savings=worst-r.total);

    const list=document.getElementById('station-list');
    list.innerHTML='';
    document.getElementById('res-meta').textContent=hasDest
      ?`${sorted.length} stations · ${baseDist.toFixed(1)} km direct · ${priority==='route'?'prioritising route':'prioritising savings'}`
      :`${sorted.length} stations near you`;

    sorted.forEach((s,i)=>{
      const el=document.createElement('div');
      el.className='scard'+(i===0?' best':'');
      el.dataset.detour=s.detour;
      // Driver-requested: is driving further than the nearest option actually
      // worth it once the extra fuel burned getting there is accounted for?
      // netVsNearest = (nearest station's own real total) - (this station's
      // real total) - positive means genuinely cheaper overall, not just a
      // lower per-litre price. Omitted for the nearest station itself
      // (comparing it to itself is meaningless) and when no baseline could
      // be computed at all (e.g. only one station found).
      let worthItHtml='';
      if(s.isNearest){
        worthItHtml='<div class="worth-it good"><i class="ti ti-map-pin"></i> This is your nearest option</div>';
      }else if(s.netVsNearest!=null){
        worthItHtml=s.netVsNearest>0
          ?`<div class="worth-it good"><i class="ti ti-check"></i> Worth it — net save $${s.netVsNearest.toFixed(2)} vs nearest, after the extra drive</div>`
          :`<div class="worth-it bad"><i class="ti ti-alert-triangle"></i> Not worth it — costs $${Math.abs(s.netVsNearest).toFixed(2)} more than the nearest option once the extra drive is included</div>`;
      }
      el.innerHTML=`
        <div>
          <div class="sc-top">
            ${hasDest?`<span>${routeBadge(s.detour)}</span>`:''}
            <span class="sc-name">${s.name}</span>
            <span class="bbadge">${s.brand}</span>
            ${s.corridorKm!=null?`<span class="corridor-badge">${s.corridorKm.toFixed(1)} km off route</span>`:''}
          </div>
          <div class="sc-meta">
            <span><i class="ti ti-map-pin"></i> ${s.toStation.toFixed(1)} km ${hasDest?'from start':'away'}</span>
            ${hasDest?`<span><i class="ti ti-flag"></i> ${s.toDest.toFixed(1)} km to dest</span>
            <span><i class="ti ti-arrows-right-left"></i> +${s.detour.toFixed(1)} km detour</span>`:''}
          </div>
          ${worthItHtml}
          <a class="nav-link" href="https://waze.com/ul?ll=${s.lat},${s.lon}&navigate=yes" target="_blank">
            <i class="ti ti-navigation"></i> Navigate via Waze
          </a>
        </div>
        <div class="price-col">
          <div class="p-eff">$${s.price.toFixed(3)}</div>
          <div class="p-sub">per litre</div>
          <div class="p-total">${s.spendLiters!=null?s.spendLiters.toFixed(1)+' L · ':''}$${s.fillCost.toFixed(2)} to fill</div>
          <div class="p-total">$${s.total.toFixed(2)} ${hasDest?'trip total':'total incl. drive'}</div>
          ${i===0?`<div class="p-best">Best value</div>`:''}
        </div>`;
      list.appendChild(el);
    });
    applyFilters();
    document.getElementById('results-section').scrollIntoView({behavior:'smooth',block:'start'});
  }

  function renderResults(results, baseDist, routeKm, routeMins, rt, hasDest){
    document.getElementById('results-section').style.display='block';
    document.getElementById('res-title').textContent=hasDest?'Route Comparison':'Nearby Stations';
    document.getElementById('route-tabs').style.display=hasDest?'flex':'none';
    document.getElementById('route-filter-group').style.display=hasDest?'flex':'none';
    if(!hasDest&&filterMode==='route')setFilter('all');
    if(hasDest){
      // Cache this route's results - only meaningful when comparing
      // fastest/shortest/eco for an actual route to a destination.
      cache[rt]={results, baseDist, routeKm, routeMins};
      activeRouteType=rt;
      renderRouteTabs(rt);
    }
    renderStationList(results, baseDist, rt, hasDest);
  }

  // ── Core search for one route type ───────────────────────────────────────────
  const CTA_LABEL='<i class="ti ti-bolt"></i> Find Best Deal';

  async function runSearch(){
    clearMessages();
    const dest=document.getElementById('dest').value.trim();
    const hasDest=dest.length>0;

    const btn=document.getElementById('cta-btn');
    btn.disabled=true;
    setProgress(5);

    // Single-button flow: get a location fix automatically rather than
    // requiring a separate manual "Detect Location" tap first.
    // detectLocation() always resolves (falls back to Sydney CBD + a
    // warning on denial/no support), so this never blocks the search.
    if(!userLat){
      setBtn('<span class="spinner"></span> Getting your location…');
      await detectLocation();
    }

    const economy  =parseFloat(document.getElementById('economy').value)||8.5;
    const tank     =parseFloat(document.getElementById('tank').value)||60;
    const gaugePct =parseFloat(document.getElementById('gauge').value)||25;
    const spendAmt =parseFloat(document.getElementById('manual-spend').value)||0;
    const fuelType =document.getElementById('fueltype').value;
    const tripMult =tripMode==='return'?2:1;
    // liters is used as fallback (auto); when spendAmt>0, per-station litres computed below
    const autoLiters=+(tank*(1-gaugePct/100)).toFixed(1);
    const rt       =routeType;
    // Corridor search needs an actual route to sample along - with no
    // destination there's nothing to build a corridor around, so this run
    // always behaves as Near Me regardless of the stored Advanced Settings
    // toggle (which stays as the driver left it for next time they do
    // enter a destination).
    const effectiveMode=hasDest?searchMode:'near';

    try{
      let dLat=null,dLon=null,routeData=null;

      if(hasDest){
        setBtn('<span class="spinner"></span> Geocoding destination…');
        try{[dLat,dLon]=await geocode(dest);}
        catch(e){showErr(e.message);btn.disabled=false;setBtn(CTA_LABEL);setProgress(0);return;}
        setProgress(12);
      }

      setBtn('<span class="spinner"></span> Authenticating with NSW FuelCheck…');
      let token;
      try{token=await getNSWToken();}
      catch(e){
        showErr('NSW FuelCheck API error: '+e.message+'<br><br>CORS restriction — run via local server or Streamlit proxy.');
        btn.disabled=false;setBtn(CTA_LABEL);setProgress(0);return;
      }
      setProgress(20);

      if(hasDest){
        setBtn(`<span class="spinner"></span> Fetching ${routeLabels[rt]} route…`);
        try{routeData=await tomtomRoute(userLat,userLon,dLat,dLon,rt);}
        catch(e){showErr('TomTom: '+e.message);btn.disabled=false;setBtn(CTA_LABEL);setProgress(0);return;}
        setProgress(30);
      }

      let rawStations=[];

      if(effectiveMode==='near'){
        const radius=parseInt(document.getElementById('radius').value)||10;
        setBtn(`<span class="spinner"></span> Fetching ${fuelType} prices within ${radius} km…`);
        try{
          const raw=await fetchStations(userLat,userLon,radius,fuelType,token);
          rawStations=parseStations(raw);
        }catch(e){showErr('NSW FuelCheck: '+e.message);btn.disabled=false;setBtn(CTA_LABEL);setProgress(0);return;}
        setProgress(55);

      }else{
        const corridorWidth=parseInt(document.getElementById('corridor-width').value)||5;
        const sampleKm=parseInt(document.getElementById('sample-km').value)||10;
        const samples=samplePolyline(routeData.points,sampleKm);
        // Each sample only fetches within its own radius, so a station sitting
        // between two samples (up to sampleKm apart) but still within corridorWidth
        // of the route can be too far from EITHER sample's center to ever be
        // fetched - never even reaching the corridor-width check below, let alone
        // being priced or ranked. The radius has to cover half the gap to the next
        // sample too, not just the corridor width itself, or coverage has holes
        // (+1km extra for the route curving between samples rather than being
        // perfectly straight).
        const fetchRadius=corridorWidth+sampleKm/2+1;
        const seen=new Set();
        for(let i=0;i<samples.length;i++){
          const[sLat,sLon]=samples[i];
          setBtn(`<span class="spinner"></span> Scanning corridor… (${i+1}/${samples.length})`);
          setProgress(30+Math.round((i/samples.length)*35));
          try{
            const raw=await fetchStations(sLat,sLon,fetchRadius,fuelType,token);
            for(const s of parseStations(raw)){
              const key=`${s.name}|${s.lat.toFixed(4)}|${s.lon.toFixed(4)}`;
              if(!seen.has(key)){
                seen.add(key);
                const off=distToPolyline(s.lat,s.lon,routeData.points);
                if(off<=corridorWidth)rawStations.push({...s,corridorKm:off});
              }
            }
          }catch{}
        }
        setProgress(65);
      }

      if(!rawStations.length){
        showWarn(hasDest
          ?'No stations found. Try increasing the search radius / corridor width in Advanced Settings.'
          :'No stations found nearby. Try increasing the search radius in Advanced Settings.');
        btn.disabled=false;setBtn(CTA_LABEL);setProgress(0);return;
      }

      setBtn('<span class="spinner"></span> Calculating road distances…');
      const baseDist=hasDest?routeData.distKm:0;
      // In corridor mode, rawStations is built by concatenating per-sample-point
      // batches in route order, not price order - each batch is individually
      // price-sorted by the API, but the combined list isn't. Sorting by price here,
      // before the cap below, ensures the cap keeps the cheapest candidates overall
      // rather than an arbitrary slice determined by which sample point happened to
      // find them first.
      rawStations.sort((a,b)=>a.price-b.price);
      const cap=Math.min(rawStations.length,25);

      // "Is the drive worth it?" (driver-requested): find the single
      // geographically NEAREST candidate (cheap straight-line distance, no
      // API call) as the "what if I just went to my closest option, price
      // be damned" baseline. It might not be among the cheapest-by-price
      // stations actually priced below, so it's given a guaranteed extra
      // slot here rather than only being considered if it happens to also
      // be cheap. Its own total is computed via the exact same real-driving-
      // distance cost math as every other candidate below, just possibly as
      // one extra (26th) priced station - straight-line distance only
      // decides WHICH station is "nearest," never its actual cost.
      let nearestIdx=-1,nearestD=Infinity;
      rawStations.forEach((s,i)=>{
        const d=haversineRaw(userLat,userLon,s.lat,s.lon);
        if(d<nearestD){nearestD=d;nearestIdx=i;}
      });
      const priceIdxs=Array.from({length:cap},(_,i)=>i);
      if(nearestIdx>=cap)priceIdxs.push(nearestIdx);

      const results=[];
      let nearestTotal=null;

      for(let n=0;n<priceIdxs.length;n++){
        const i=priceIdxs[n];
        const s=rawStations[i];
        setProgress(65+Math.round((n/priceIdxs.length)*30));
        const legA=await tomtomDist(userLat,userLon,s.lat,s.lon);
        const legB=hasDest?await tomtomDist(s.lat,s.lon,dLat,dLon):0;
        const detour=hasDest?Math.max(0,(legA+legB)-baseDist):0;
        const liters=spendAmt>0?spendAmt/s.price:autoLiters;
        const fillCost=spendAmt>0?spendAmt:liters*s.price;
        const legACost=(legA*economy/100)*s.price;
        const legBCost=hasDest?(legB*tripMult*economy/100)*s.price:0;
        const total=fillCost+legACost+legBCost;
        const isNearest=(i===nearestIdx);
        if(isNearest)nearestTotal=total;
        results.push({...s,toStation:legA,toDest:hasDest?legB:null,detour,fillCost,total,
          isNearest,corridorKm:s.corridorKm??null,spendLiters:spendAmt>0?liters:null});
      }
      if(nearestTotal!=null){
        results.forEach(r=>{r.netVsNearest=r.isNearest?0:(nearestTotal-r.total);});
      }

      setProgress(100);
      renderResults(results,baseDist,hasDest?routeData.distKm:null,hasDest?routeData.mins:null,rt,hasDest);

    }catch(e){showErr(e.message);}

    btn.disabled=false;
    setBtn(CTA_LABEL);
    setProgress(0);
  }

  // ── Autocomplete ──────────────────────────────────────────────────────────────
  let acIndex=-1,acResults=[],acTimer=null;
  function onDestInput(){
    clearTimeout(acTimer);
    const q=document.getElementById('dest').value.trim();
    if(q.length<3){closeAc();return;}
    acTimer=setTimeout(()=>fetchAc(q),280);
  }
  async function fetchAc(q){
    try{
      const r=await fetch(`https://nominatim.openstreetmap.org/search?q=${encodeURIComponent(q)}&format=json&limit=6&countrycodes=au`,{headers:{'User-Agent':'FuelOptimizer/2.0'}});
      acResults=(await r.json()).map(d=>d.display_name);renderAc();
    }catch{}
  }
  function renderAc(){
    const list=document.getElementById('ac-list');
    if(!acResults.length){closeAc();return;}
    acIndex=-1;
    list.innerHTML=acResults.map((r,i)=>`<div class="ac-item" onmousedown="pickAc(${i})">${r}</div>`).join('');
    list.classList.add('open');
  }
  function pickAc(i){document.getElementById('dest').value=acResults[i];closeAc();}
  function closeAc(){document.getElementById('ac-list').classList.remove('open');acResults=[];acIndex=-1;}
  function onDestKey(e){
    const items=document.querySelectorAll('.ac-item');
    if(!items.length)return;
    if(e.key==='ArrowDown'){e.preventDefault();acIndex=Math.min(acIndex+1,items.length-1);highlightAc(items);}
    else if(e.key==='ArrowUp'){e.preventDefault();acIndex=Math.max(acIndex-1,0);highlightAc(items);}
    else if(e.key==='Enter'&&acIndex>=0){e.preventDefault();pickAc(acIndex);}
    else if(e.key==='Escape')closeAc();
  }
  function highlightAc(items){
    items.forEach((el,i)=>el.classList.toggle('active',i===acIndex));
    if(acIndex>=0)items[acIndex].scrollIntoView({block:'nearest'});
  }
  document.addEventListener('click',e=>{if(!e.target.closest('.dest-wrap'))closeAc();});

  // init
  updateSummary();
