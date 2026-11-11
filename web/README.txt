ORBIT GPS - WEB VERSION FOR IPHONE (orbitgps.ca/go/)
=====================================================

UPLOAD
1. In hosting.com's File Manager, open public_html (the orbitgps.ca folder).
2. Upload this zip there and Extract it. You get a folder "go" (keep its .htaccess file).
3. Open https://orbitgps.ca/go/ in a browser to check: the map of Terrace should appear.

INSTALL ON AN IPHONE
Open orbitgps.ca/go in Safari > Share button > Add to Home Screen > Add.

SETTINGS (go/config.json, can be edited any time, no new upload of the rest needed)
- "contact": put the public email for Orbit here (shown in About). The free routing
  server's rules (FOSSGIS) ask for a contact email on the website.
- "routing": the route server. Default is the free FOSSGIS Valhalla server
  (max 1 request a second per person; fine for a small app). If Orbit gets big,
  point this at another Valhalla server without changing the app.
- "maps": where the region map files are (maps.orbitgps.ca, already set up).

UPDATES
Upload the new "go" folder over the old one. Phones pick up the new version the next
time Orbit is opened with a connection (sw.js has a new version number each build).

WHAT IT USES
- Map: the same region map files the Android app downloads, read from maps.orbitgps.ca
  (CORS rule "orbitgps-web" on the R2 bucket allows orbitgps.ca to read them).
- Voice: the app's Kokoro clips (go/voice, MP3), with the phone's voice as backup.
- Turn warnings: the same rules as the app (js/nav.js is a port of the Kotlin code).
- Search: Photon + BC Address Geocoder; nearby categories: Overpass (OpenStreetMap).
- Routes: Valhalla (online). A planned route keeps guiding with no signal; the map
  along the route is saved on the phone when you press Go.

LIMITS (Apple's rules for web apps, not fixable in a web page)
- GPS stops when the screen locks or another app is in front: keep Orbit on screen.
- No CarPlay. New routes and search need a data connection.

Testing on a computer: orbitgps.ca/go/#sim drives the planned route by itself
(#sim=4 at four times speed).
