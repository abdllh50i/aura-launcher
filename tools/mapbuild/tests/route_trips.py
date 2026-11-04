"""The 50 real trips, plus 6 at the edges of what a router can do. (lat, lon), read from places.db or OSM, not invented; run_route_trips.py asks both engines.

Ids from ../trips.py keep their old id and the same points, so earlier answers can be compared. Groups:
  street     streets of Dammam, Khobar, Dhahran, Saihat, Qatif, Tarout, Safwa
  jubail     Jubail town and Jubail Industrial City (roundabouts)
  rastanura  Ras Tanura
  province   motorways of the Eastern Province
  ahsa       Al Ahsa: Hofuf, Mubarraz
  riyadh     inside Riyadh
  long       long trips inside the Kingdom
  makkah     non-Muslim route rules: a KNOWN LIMIT, no engine here knows them (see the report)
  gated      destination inside a guarded compound or an unreachable place
  side       start or end on a service road
"""

TRIPS = [
    # streets of the Eastern Province
    ("k1", "street", "Al-Rashed Mall to Khobar Water Tower", (26.29077, 50.18098), (26.30778, 50.23189)),
    ("k5", "street", "Khobar City Walk to South Corniche Park", (26.30636, 50.18472), (26.26454, 50.21432)),
    ("d7", "street", "Imam Abdulrahman University West Campus to King Fahad Park", (26.38799, 50.18629), (26.42057, 50.12003)),
    ("d3", "street", "Dammam Souq to Westfield Dammam", (26.44539, 50.10290), (26.39126, 50.03736)),
    ("d4", "street", "Marina Mall to Saudi German Hospital", (26.45499, 50.10583), (26.37758, 49.99203)),
    ("d1", "street", "King Fahad Specialist Hospital to Al Othaim Mall", (26.41129, 50.10142), (26.39968, 50.11691)),
    ("k7", "street", "Almosa Hospital to Security Forces Medical City", (26.36023, 50.23004), (26.30280, 50.19937)),
    ("ithra", "street", "Ithra to Al-Rashed Mall", (26.335942, 50.120112), (26.29077, 50.18098)),
    ("kfupm", "street", "KFUPM gate to Holiday Inn Khobar Corniche", (26.30929, 50.150753), (26.316801, 50.216991)),
    ("saihat", "street", "LuLu Saihat to Khobar corniche", (26.487133, 50.047588), (26.294165, 50.224438)),
    ("tarout", "street", "Qatif Plaza to Tarout Castle, over the bridge", (26.550917, 50.015458), (26.569278, 50.065952)),
    ("safwa", "street", "Safwa General Hospital to Qatif City Mall", (26.644031, 49.962018), (26.557434, 50.037908)),
    ("qatif", "street", "Qatif Central Hospital to Dammam corniche", (26.524427, 49.968312), (26.458807, 50.098389)),
    ("dam-khb", "street", "Dammam corniche to Khobar corniche", (26.46067, 50.10672), (26.29442, 50.22216)),
    # Jubail town and Industrial City
    ("j1", "jubail", "Jubail General Hospital to Jubail Center", (26.99456, 49.65933), (27.01282, 49.66133)),
    ("j5", "jubail", "Mouwasat Hospital to Al Fanateer Corniche", (27.13732, 49.53670), (27.15825, 49.57289)),
    ("j6", "jubail", "Jubail town to Al Mana Hospital, Industrial City", (27.00460, 49.65860), (27.09386, 49.56874)),
    ("ji-deffi", "jubail", "Al Deffi Park to Royal Commission Hospital", (27.142503, 49.531442), (27.14156, 49.56527)),
    ("ji-round", "jubail", "Al Fanateer Corniche to Al Huwaylat Mall, roundabouts", (27.15825, 49.57289), (27.09783, 49.57223)),
    ("ji-town", "jubail", "Al Fanateer Mall to King Star Mall in town", (27.13582, 49.56668), (27.00106, 49.66498)),
    ("ji-khair", "jubail", "Mouwasat Hospital to Ras Al Khair Beach", (27.13732, 49.53670), (27.534889, 49.128132)),
    # Ras Tanura
    ("rt-jubail", "rastanura", "Ras Tanura Police to King Star Mall, Jubail", (26.706226, 50.069737), (27.00106, 49.66498)),
    ("rt-dammam", "rastanura", "Ras Tanura Pizza Hut to Dammam Medical Complex", (26.706322, 50.052662), (26.43165, 50.08381)),
    ("rt-safwa", "rastanura", "Safwa to Ras Tanura town", (26.648464, 49.946659), (26.707417, 50.067352)),
    # motorways of the province
    ("jubail-dammam", "province", "Jubail town to Dammam", (27.0046, 49.6586), (26.4207, 50.0888)),
    ("airport", "province", "Ithra to King Fahd International Airport", (26.335942, 50.120112), (26.46856, 49.796791)),
    ("abqaiq", "province", "Abqaiq General Hospital to Dammam Railway Station", (25.925388, 49.647488), (26.40974, 50.12631)),
    ("halfmoon", "province", "Khobar corniche to Half Moon Bay", (26.29442, 50.22216), (26.174765, 50.06649)),
    ("nairyah", "province", "Nairyah to Dammam", (27.474154, 48.478935), (26.4207, 50.0888)),
    ("khafji", "province", "Khafji Mall to Jubail town", (28.427275, 48.476856), (27.0046, 49.6586)),
    ("causeway", "province", "Khobar to the King Fahd Causeway's passport island", (26.29442, 50.22216), (26.18622, 50.32900)),
    # Al Ahsa
    ("ahsa-mall", "ahsa", "Al Ahsa Mall to King Fahad Hospital, Hofuf", (25.329092, 49.549805), (25.357563, 49.563146)),
    ("ahsa-hosp", "ahsa", "Almoosa Hospital, Hofuf to Al Jaber Hospital, Mubarraz side", (25.346973, 49.612095), (25.407726, 49.55975)),
    ("ahsa-qara", "ahsa", "Al Qara Mountain to Hofuf train station", (25.41448, 49.686787), (25.386559, 49.56852)),
    ("hofuf-dammam", "ahsa", "Hofuf train station to Dammam", (25.386559, 49.56852), (26.4207, 50.0888)),
    ("hofuf-riyadh", "ahsa", "Hofuf to Kingdom Centre, Riyadh", (25.369658, 49.586522), (24.711348, 46.674411)),
    # Riyadh
    ("ruh-air", "riyadh", "Kingdom Centre to King Khalid International Airport", (24.711348, 46.674411), (24.958915, 46.706528)),
    ("ruh-dir", "riyadh", "Diriyah Art Futures to Granada Mall", (24.740342, 46.572788), (24.781208, 46.729855)),
    ("ruh-east", "riyadh", "Al Faisaliah Mall to Granada Mall, east Riyadh", (24.689478, 46.685686), (24.640797, 46.772793)),
    # long trips
    ("dammam-riyadh", "long", "Dammam to Riyadh", (26.4207, 50.0888), (24.7136, 46.6753)),
    ("riyadh-jeddah", "long", "Riyadh to Jeddah", (24.7136, 46.6753), (21.5433, 39.1728)),
    ("dammam-jeddah", "long", "Dammam to Jeddah", (26.4207, 50.0888), (21.5433, 39.1728)),
    ("abha-riyadh", "long", "Abha to Riyadh", (18.2164, 42.5053), (24.7136, 46.6753)),
    ("riyadh-buraydah", "long", "Riyadh to Buraydah", (24.7136, 46.6753), (26.331667, 43.971667)),
    ("riyadh-tabuk", "long", "Riyadh to Tabuk", (24.7136, 46.6753), (28.401254, 36.567049)),
    ("jed-madinah", "long", "Jeddah to the Prophet's Mosque, Madinah", (21.5433, 39.1728), (24.468611, 39.610756)),
    # Makkah: nobody here knows who may enter
    ("jed-makkah", "makkah", "Jeddah airport station to the Grand Mosque", (21.6585, 39.174636), (21.423894, 39.825048)),
    ("jed-taif", "makkah", "Jeddah to Taif Mall, which must not pass Makkah's forbidden zone for a non-Muslim", (21.5433, 39.1728), (21.254766, 40.410515)),
    # gated
    ("gate-camp", "gated", "Al-Rashed Mall to Dhahran Hills Elementary School, inside Aramco's camp", (26.29077, 50.18098), (26.290298, 50.106827)),
    ("gate-air", "gated", "Dammam to King Abdulaziz Air Base Hospital", (26.4207, 50.0888), (26.284409, 50.150787)),
    # edges beyond the 50
    ("gate-rt", "gated", "Dammam to Ras Tanura Elementary School, Aramco's camp", (26.4207, 50.0888), (26.72492, 50.067646)),
    ("gate-jana", "gated", "Jubail to Jana Island in the Gulf, no road", (27.0046, 49.6586), (27.37, 49.90)),
    ("ferry", "long", "Jazan General Hospital to Farasan General Hospital, by ferry", (16.896163, 42.555337), (16.695623, 42.118831)),
    ("side-end", "side", "Pavilion AlKhobar to the service road beside King Fahd Road", (26.33025, 50.21285), (26.320872, 50.208461)),
    ("side-start", "side", "From King Khalid Road's service road to Khobar City Walk", (26.267245, 50.187774), (26.30636, 50.18472)),
    ("side-jubail", "side", "From the service road beside the Dhahran-Jubail road to Safwa", (26.60864, 49.933408), (26.648464, 49.946659)),
]
