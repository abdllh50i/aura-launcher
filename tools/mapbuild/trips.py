"""The fixed trips: reference answers and comparisons all use these. (lat, lon); places are OSM names in the extract."""

LONG = [
    ("jubail-dammam", "Jubail town to Dammam", (27.0046, 49.6586), (26.4207, 50.0888)),
    ("khobar-corniche-dammam-corniche", "Khobar corniche to Dammam corniche", (26.29442, 50.22216), (26.46067, 50.10672)),
    ("dammam-riyadh", "Dammam to Riyadh", (26.4207, 50.0888), (24.7136, 46.6753)),
    ("riyadh-jeddah", "Riyadh to Jeddah", (24.7136, 46.6753), (21.5433, 39.1728)),
    ("dammam-jeddah", "Dammam to Jeddah", (26.4207, 50.0888), (21.5433, 39.1728)),
    ("abha-riyadh", "Abha to Riyadh", (18.2164, 42.5053), (24.7136, 46.6753)),
]

SHORT = [
    # Dammam
    ("d1", "King Fahad Specialist Hospital to Al Othaim Mall", (26.41129, 50.10142), (26.39968, 50.11691)),
    ("d2", "Dammam Railway Station to Dammam Medical Complex", (26.40974, 50.12631), (26.43165, 50.08381)),
    ("d3", "Dammam Souq to Westfield Dammam", (26.44539, 50.10290), (26.39126, 50.03736)),
    ("d4", "Marina Mall to Saudi German Hospital", (26.45499, 50.10583), (26.37758, 49.99203)),
    ("d5", "Main Dammam Station to Prince Mohammad bin Fahd Stadium", (26.45612, 50.10638), (26.42693, 50.11453)),
    ("d6", "Dareen Mall to Alshati Mall", (26.46238, 50.06489), (26.45481, 50.12087)),
    ("d7", "Imam Abdulrahman University West Campus to King Fahad Park", (26.38799, 50.18629), (26.42057, 50.12003)),
    # Khobar
    ("k1", "Al-Rashed Mall to Khobar Water Tower", (26.29077, 50.18098), (26.30778, 50.23189)),
    ("k2", "Mall of Dhahran to Al Mana Hospital Khobar", (26.30625, 50.16855), (26.29414, 50.21839)),
    ("k3", "King Fahad University Hospital to Al Shubaily Grand Mall", (26.29961, 50.18925), (26.22776, 50.21117)),
    ("k4", "Pavilion AlKhobar to Dr. Sulaiman Al Habib Hospital", (26.33025, 50.21285), (26.31067, 50.21225)),
    ("k5", "Khobar City Walk to South Corniche Park", (26.30636, 50.18472), (26.26454, 50.21432)),
    ("k6", "Al Qadsiah Stadium to Prince Mohammed bin Fahad University", (26.29418, 50.20319), (26.14641, 50.09218)),
    ("k7", "Almosa Specialized Hospital to Security Forces Medical City", (26.36023, 50.23004), (26.30280, 50.19937)),
    # Jubail
    ("j1", "Jubail General Hospital to Jubail Center", (26.99456, 49.65933), (27.01282, 49.66133)),
    ("j2", "King Star Shopping Mall to Jubail Corniche", (27.00106, 49.66498), (27.03463, 49.63819)),
    ("j3", "Al Huwaylat Mall to Royal Commission Hospital", (27.09783, 49.57223), (27.14156, 49.56527)),
    ("j4", "Al Fanateer Mall to Jubail Industrial College", (27.13582, 49.56668), (27.10453, 49.57053)),
    ("j5", "Mouwasat Hospital to Al Fanateer Corniche", (27.13732, 49.53670), (27.15825, 49.57289)),
    ("j6", "Jubail town to Al Mana Hospital, Industrial City", (27.00460, 49.65860), (27.09386, 49.56874)),
]

# Edge checks of the cut: the Saudi end of the causeway, and border posts
EDGES = [
    ("causeway", "Khobar to the King Fahd Causeway's passport island", (26.29442, 50.22216), (26.18622, 50.32900)),
    ("salwa", "Riyadh to the Salwa post (Qatar)", (24.7136, 46.6753), (24.74050, 50.76000)),
    ("batha", "Dammam to the Al Batha post (UAE)", (26.4207, 50.0888), (24.2520, 51.5840)),
    ("khafji", "Dammam to Khafji post (Kuwait)", (26.4207, 50.0888), (28.5240, 48.4450)),
]


def request(a, b, alternates=2):
    """The request the app will send (maps.md section 7.4)."""
    return {
        "locations": [{"lat": a[0], "lon": a[1], "type": "break"}, {"lat": b[0], "lon": b[1], "type": "break"}],
        "costing": "auto",
        "costing_options": {"auto": {"top_speed": 140, "use_tolls": 0.5, "use_tracks": 0.0}},
        "format": "osrm",
        "shape_format": "geojson",
        "alternates": alternates,
        "units": "kilometers",
        "language": "en-US",
        "turn_lanes": True,
    }
