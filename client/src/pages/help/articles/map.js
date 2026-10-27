const M = ['MANAGER'];
const C = 'Ecosystem Map';

export default [
  {
    id: 'map-overview',
    title: 'What the Ecosystem Map shows and when agents are tracked',
    app: 'web', category: C, roles: M,
    summary: 'A tour of the Ecosystem Map and the rules about when, and with whose consent, agents are tracked.',
    keywords: ['map', 'gps', 'tracking', 'agents', 'consent', 'work hours', 'privacy', 'schedule'],
    blocks: [
      { type: 'p', text: 'Open **Ecosystem Map** in the sidebar. It shows your stores as pins, your field agents where they were last seen, and, once you pick one agent, the route they travelled on a given day. Only managers can see this page.' },
      { type: 'h', text: 'What is on the page' },
      { type: 'list', items: [
        'The map on the right, with the layer buttons **Stores**, **Agents (live)**, **Route** and **Stops**. Tap a button to hide or show that layer.',
        'The filter panel on the left: **Agent**, **Date**, **Time from**, **Time to**, **Store**, **Or a point on the map** and **Min stop**.',
        'Under the filters: **Device alerts now** (when you are looking at all agents), or the day summary, **Device log**, **Play route** and **Timeline** (when you pick one agent).',
        '**Unplaced stores** at the bottom of the panel, for stores that have no pin yet.'
      ] },
      { type: 'h', text: 'When agents are tracked' },
      { type: 'p', text: 'The agent tablets record their location **only during the work schedule**, never at night or on days off. The default is Monday to Saturday, 7:00 to 18:00 (Manila time). The server also throws away any point that arrives from outside those hours.' },
      { type: 'steps', items: [
        'Open **Admin** in the sidebar and choose the **Tracking** tab.',
        'Tap the working days, set **Start** and **End**, then tap **Save schedule**.'
      ] },
      { type: 'h', text: 'Consent' },
      { type: 'p', text: 'Before an agent is tracked, the Marbingo Field app shows a notice explaining what is recorded and asks the agent to agree and to allow location "all the time". Tracking does not start until they do. Tell new agents about it when you hire them.' },
      { type: 'note', text: 'Location history is kept for 365 days and then deleted. The list of stops (where an agent stayed) is kept longer.' },
      { type: 'p', text: 'Next: [[map-live-agents|Live agents and their status]], [[map-agent-day|Look at one agent\'s day]].' }
    ]
  },
  {
    id: 'map-live-agents',
    title: 'See where your agents are right now',
    app: 'web', category: C, roles: M,
    summary: 'Read the live agent markers and what Tracking, Signal lost and Off duty mean.',
    keywords: ['live', 'status', 'signal lost', 'off duty', 'tracking', 'battery', 'last seen'],
    blocks: [
      { type: 'p', text: 'With **Agent** set to **All agents** and the **Agents (live)** layer on, each agent who has been seen shows as a marker with their initials. The map refreshes by itself every 30 seconds while the tab is open.' },
      { type: 'steps', items: [
        'Open **Ecosystem Map**.',
        'Tap an agent\'s marker to see a small card.',
        'The card shows their status, **Last seen**, speed, battery, and **Today** (number of stops and kilometres).'
      ] },
      { type: 'h', text: 'The three statuses' },
      { type: 'list', items: [
        '**Tracking**: the tablet sent a location in the last 5 minutes. All is normal.',
        '**Signal lost**: it is work time but nothing has arrived for a while. The tablet may be in a dead zone, off, or out of battery. See [[map-device-log|the Device log]] to find out why.',
        '**Off duty**: outside the work schedule. These markers are hidden unless you select that agent.'
      ] },
      { type: 'tip', text: 'Agents whose tablet has a problem right now are listed under **Device alerts now**. Tap a name to jump to that agent\'s day. See [[map-device-alerts|Device alerts now]].' },
      { type: 'faq', items: [
        { q: 'An agent is missing from the map.', a: 'Either they have never been tracked (no consent yet, or the app was never opened on duty) or they are off duty and not selected. Pick them in **Agent** to see their last position.' },
        { q: 'The marker shows an old time.', a: 'The card shows how long ago the last point arrived. If the tablet had no internet, the points are saved on the tablet and appear later, so the marker may catch up.' }
      ] }
    ]
  },
  {
    id: 'map-agent-day',
    title: 'Look at one agent\'s day: route, stops and playback',
    app: 'web', category: C, roles: M,
    summary: 'Pick an agent and a date to see the route, where they stopped, a timeline and a replay.',
    keywords: ['route', 'stops', 'timeline', 'playback', 'summary', 'distance', 'unlisted', 'replay'],
    blocks: [
      { type: 'steps', items: [
        'Open **Ecosystem Map**.',
        'In **Agent**, choose the person. Today is selected in **Date**; change it to look at a past day.',
        'The route is drawn on the map and the panel fills with the day\'s details.'
      ] },
      { type: 'h', text: 'Day summary' },
      { type: 'p', text: 'The summary card shows **Distance**, **Moving**, **Stopped**, **Stops** and **Stores visited**, plus when the agent was first and last seen that day.' },
      { type: 'h', text: 'Timeline' },
      { type: 'p', text: 'The **Timeline** lists each stop in order with the arrival and leaving time and how long it lasted, with a **Travel** line between stops showing distance and minutes. A stop at a known store shows a **Store** badge and the store name. A stop that is not near any store shows **Unlisted** and the name "Unlisted place". Tap a row to zoom the map to it.' },
      { type: 'note', text: 'A stop is counted when the agent stays put for at least the **Min stop** time (3 minutes by default). Change **Min stop** to 1, 5, 10 or 15 minutes to ignore or include short stops. A stop is matched to a store when it is within about 75 metres of the store pin, so a store with no pin, or a wrong pin, shows as **Unlisted**.' },
      { type: 'h', text: 'Play route' },
      { type: 'p', text: 'The **Play route** card replays the day. Tap **Play** (it becomes **Pause**), drag the slider to jump to a time, and choose a speed from ×30 to ×300. A marker moves along the map and the clock shows the time of that point.' },
      { type: 'tip', text: 'No data? A message says "No GPS data" for that agent and day. It could be a day off, or the tablet had no location that day. Check the [[map-device-log|Device log]].' }
    ]
  },
  {
    id: 'map-filters',
    title: 'Filter by time, store or a spot on the map',
    app: 'web', category: C, roles: M,
    summary: 'Answer "who visited this store?" or "who was near here this afternoon?".',
    keywords: ['filter', 'who visited', 'store visits', 'radius', 'point', 'time range', 'visits here'],
    blocks: [
      { type: 'h', text: 'Narrow the day by time' },
      { type: 'p', text: 'Fill in **Time from** and **Time to** to look only at, say, 1:00 to 3:00 pm. The route, summary and stops all follow the window.' },
      { type: 'h', text: 'Who visited this store?' },
      { type: 'steps', items: [
        'Leave **Agent** on **All agents**.',
        'Choose the shop in **Store** (the default is **Any location**). Only stores that already have a pin are listed.',
        'Pick the **Date**. The **Visits here** list shows every agent who stopped at that store, with the time and how long they stayed.'
      ] },
      { type: 'tip', text: 'You can also tap a store pin on the map. Its card shows the total visits, the last visit, time on site and the five most recent visits.' },
      { type: 'h', text: 'Who was near this spot?' },
      { type: 'steps', items: [
        'Tap **Pick a point on the map**. The button changes to **Click the map…**.',
        'Click the place on the map.',
        'Choose a **Radius**: 100 m, 300 m, 500 m or 1 km. A circle is drawn and **Visits here** lists the stops inside it.',
        'Tap **Clear** to remove the point.'
      ] },
      { type: 'note', text: 'A store and a point are alternatives. Choosing one clears the other. The filters apply to one **Date** at a time.' }
    ]
  },
  {
    id: 'map-store-pins',
    title: 'Place and move store pins',
    app: 'web', category: C, roles: M,
    summary: 'Put unplaced stores on the map and correct a pin that is in the wrong spot.',
    keywords: ['pin', 'place', 'move', 'unplaced', 'store location', 'drag'],
    blocks: [
      { type: 'p', text: 'Stores get a pin automatically when an agent saves their location in the app. A store with no location appears under **Unplaced stores**. Pins matter because the map matches stops to stores using them.' },
      { type: 'h', text: 'Place a store' },
      { type: 'steps', items: [
        'In **Unplaced stores**, find the shop and tap **Place on map**.',
        'A bar says to click the map to place it. Click the right spot (zoom in first for accuracy).',
        'A message confirms the store was placed. It leaves the unplaced list.'
      ] },
      { type: 'p', text: 'To back out, tap **Cancel** in the bar.' },
      { type: 'h', text: 'Move a pin' },
      { type: 'steps', items: [
        'Tap the store\'s pin to open its card.',
        'Tap **Move pin**. The button now reads **Drag the pin, then done**.',
        'Drag the pin to the right spot and drop it. The new position is saved right away.'
      ] },
      { type: 'note', text: 'The card says where the location came from: "from agent GPS" or "placed manually", with its accuracy when known. A pin you place or drag is marked as manually placed.' }
    ]
  },
  {
    id: 'map-device-log',
    title: 'Read the Device log',
    app: 'web', category: C, roles: M,
    summary: 'Understand every gap type and colour, and the No location versus Offline totals.',
    keywords: ['device log', 'gaps', 'battery died', 'airplane', 'fake gps', 'mock location', 'gps off', 'permission', 'no signal', 'clock', 'tablet off', 'app stopped'],
    blocks: [
      { type: 'p', text: 'When you select one agent, a **Device log** card shows what happened to their tablet during work hours that day. The tablet notes when something changes (switched off, GPS off, data off and so on) and the log turns those into "gaps" along a bar from the start to the end of the work day.' },
      { type: 'h', text: 'The two totals' },
      { type: 'list', items: [
        '**No location**: time when the tablet could not record where the agent was (tablet off, app stopped, fake GPS, GPS off, or permission removed). This time is a real hole in the route.',
        '**Offline**: time with no internet (airplane mode, mobile data off, or no signal). The location was **still recorded** and uploads later, so the route is not lost.'
      ] },
      { type: 'h', text: 'Colours' },
      { type: 'list', items: [
        'Red: no location was recorded.',
        'Amber: location was switched off or blocked.',
        'Blue: the tablet was offline on purpose or by setting.',
        'Grey: minor or informational.'
      ] },
      { type: 'h', text: 'Each gap type' },
      { type: 'list', items: [
        '**Tablet switched off** (red): the tablet was powered down. If the battery was at 5% or less, it says **Tablet off (battery died)**. If the tablet shut down without warning, the start is a good guess, marked **start time approximate**.',
        '**App stopped (force-stopped or closed by the phone)** (red): the tracking app was closed for more than 3 minutes, either by the agent or by the phone to save battery.',
        '**Fake GPS app in use** (red): the tablet was sending made-up locations from a mock-location app. Those locations are not saved.',
        '**GPS / location turned off** (amber): location was switched off in the quick settings.',
        '**Location permission removed** (amber): the agent took away the "all the time" location permission.',
        '**Airplane mode on** (blue): GPS is still recorded and uploaded later.',
        '**Mobile data switched off** (blue): same, the trail uploads once data is back.',
        '**No signal** (grey): data is on but there is no internet (a dead zone). Gaps under 2 minutes are ignored.',
        '**Automatic time turned off** (grey): the date and time setting was switched to manual.'
      ] },
      { type: 'h', text: 'Clock changes' },
      { type: 'p', text: 'These appear as single moments in the list. **Clock changed by hand** means someone set the time on the tablet. **Tablet clock is … ahead / behind — times may be off** means the tablet\'s clock differs from the server\'s by 5 minutes or more, so times from that tablet may be shifted.' },
      { type: 'p', text: 'Each row shows the start and end time, how long it lasted, and notes like "battery 3%" or "GPS still recorded, uploaded later". A gap that is still going shows "now" as its end. If nothing happened you will see "No gaps: the tablet stayed on and connected."' },
      { type: 'p', text: 'Read this with [[map-fair-use|Using the Device log fairly]].' }
    ]
  },
  {
    id: 'map-device-alerts',
    title: 'Device alerts now',
    app: 'web', category: C, roles: M,
    summary: 'See which agents have a tablet problem during work hours and open their day.',
    keywords: ['alerts', 'device alerts', 'problem', 'signal lost', 'live'],
    blocks: [
      { type: 'p', text: 'On **Ecosystem Map** with **All agents** selected, a **Device alerts now** card lists agents whose tablet has a problem at this moment. It only appears when there is at least one.' },
      { type: 'list', items: [
        'An agent with an open gap shows its description and when it began, for example "Mobile data switched off since 9:12 AM". The dot colour matches the [[map-device-log|Device log]] colours.',
        'An agent in **Signal lost** with no known cause shows "No update since" and the last time they were heard from.'
      ] },
      { type: 'steps', items: [
        'Tap a name in the list.',
        'The page switches to that agent for today, with their route and Device log, so you can see what happened.'
      ] },
      { type: 'note', text: 'No signal alone is not shown as an alert, since brief dead zones are normal. Alerts show only during work hours.' }
    ]
  },
  {
    id: 'map-fair-use',
    title: 'Using the Device log fairly',
    app: 'web', category: C, roles: M,
    summary: 'How to treat tracking data as a reason to talk, not as automatic proof, and what agents were told.',
    keywords: ['fair', 'privacy', 'data privacy act', 'consent', 'policy', 'discipline', 'conversation'],
    blocks: [
      { type: 'p', text: 'The map and the Device log are tools to help you support your agents and understand their day. They are not a lie detector.' },
      { type: 'list', items: [
        'Dead zones are real, especially in the hills and on islands. **No signal** gaps are often nobody\'s fault.',
        'Batteries die, especially on older tablets. A gap marked **Tablet off (battery died)** or **start time approximate** is often this.',
        'Phones sometimes close apps to save battery, which shows as **App stopped**. Some brands do this aggressively.',
        'Offline time is not missing time: the trail uploads later.',
        'Clock problems can shift times. Check for **Tablet clock is … ahead / behind**.'
      ] },
      { type: 'h', text: 'A fair way to use it' },
      { type: 'steps', items: [
        'Look for patterns over several days, not one gap.',
        'Ask the agent first. Many gaps have simple explanations (a drained battery, a charger that did not work).',
        'Treat red gaps such as **Fake GPS app in use**, or repeated **GPS / location turned off** at the same time of day, as a reason for a conversation, not as automatic proof of wrongdoing.',
        'Keep what you see to the people who need to know.'
      ] },
      { type: 'h', text: 'What agents were told' },
      { type: 'p', text: 'Agents see an in-app notice and give consent before tracking begins. It explains that location is recorded only during work hours, that the shop can see their route and stops, and how the Data Privacy Act applies. Location history is deleted after 365 days.' },
      { type: 'warn', text: 'Location and device data is personal data under the Data Privacy Act. Use it only for running the business (visits, safety, support), do not share it outside the people who need it, and keep tracking to the work schedule you set in **Admin**.' }
    ]
  },
  {
    id: 'map-faq',
    title: 'Ecosystem Map questions and answers',
    app: 'web', category: C, roles: M,
    summary: 'Quick answers about straight lines, missing trails, unlisted stops and more.',
    keywords: ['faq', 'straight line', 'raw gps', 'road snapping', 'missing', 'trail', 'unlisted', 'offline'],
    blocks: [
      { type: 'faq', items: [
        { q: 'Why does the route have a straight line?', a: 'A straight line usually means the tablet had a gap with no location (for example it was off or in a dead zone), so the map just joins the last point before to the first point after. Check the Device log for that time. A small note, "Showing raw GPS trail (road snapping unavailable)", means the route is drawn from the raw GPS points instead of following roads.' },
        { q: 'An agent turned mobile data off. Is the trail lost?', a: 'No. GPS keeps recording without data and the points are uploaded when the tablet is online again. The log shows this as **Mobile data switched off** or **Airplane mode on** under **Offline**, with the note "GPS still recorded, uploaded later". Refresh the day later to see the full trail.' },
        { q: 'What is the difference between No location and Offline?', a: '**No location** means nothing was recorded for that time. **Offline** means no internet, but the location was recorded and will arrive later.' },
        { q: 'Why does a stop say Unlisted?', a: 'It is not within about 75 metres of any store pin. Either it really is another place, or the store has no pin or the pin is off. See [[map-store-pins|Place and move store pins]].' },
        { q: 'Why is a store missing from the Store list?', a: 'Only stores that have a map location are listed. Place unplaced stores first.' },
        { q: 'Why are there no points outside work hours?', a: 'By design. Tracking only runs inside the schedule in **Admin**, **Tracking**. Points outside it are refused.' },
        { q: 'The Device log card is not showing.', a: 'It shows only when one agent is selected and the selected day was a working day.' },
        { q: 'Can other roles see the map?', a: 'No, only managers.' }
      ] }
    ]
  }
];
