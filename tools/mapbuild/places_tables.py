"""The word tables behind places.db, written by hand for Saudi usage. Nothing here is copied from a third party.

CATS      the categories of table cat: key, Arabic, English, icon group, how much a place of this kind matters on a map
OVERTURE  Overture's taxonomy words to a category key
OSM rules osm_category() below
SYNONYMS  what people type or say, to category keys (table syn); a Gulf speaker's words first
ROLES     words that are never required in a query: road words, honorifics, company words, stop words
DICT      Latin word <-> Arabic word, for search only (index time): a Latin-named shop answers to its Arabic words
BRANDS    brands both ways, seeds for what the data's own brand:ar and name:ar pairs do not hold
"""

import re

# Icon groups, in the order of nav/Poi.kt's kinds, so that the app can colour by them.
OTHER, FOOD, CAFE, SHOP, FUEL, TRANSPORT, HEALTH, WORSHIP, LODGING, BANK, SCHOOL, PARK, SIGHT, PUBLIC = range(14)

# key, Arabic, English, icon, map weight (added to the rank: a hospital matters more on a map than an accountant)
CATS = [
    ("restaurant", "مطعم", "Restaurant", FOOD, 8),
    ("fast_food", "وجبات سريعة", "Fast food", FOOD, 8),
    ("pizza", "بيتزا", "Pizza", FOOD, 8),
    ("burger", "برجر", "Burger", FOOD, 8),
    ("chicken", "دجاج", "Chicken", FOOD, 8),
    ("seafood", "مأكولات بحرية", "Seafood", FOOD, 8),
    ("breakfast", "فطور", "Breakfast", FOOD, 6),
    ("bakery", "مخبز", "Bakery", FOOD, 6),
    ("sweets", "حلويات", "Sweets", FOOD, 4),
    ("juice", "عصيرات", "Juice", FOOD, 4),
    ("coffee", "قهوة", "Coffee", CAFE, 10),
    ("tea", "شاي", "Tea", CAFE, 2),
    ("lounge", "مقهى", "Lounge", CAFE, 0),
    ("fuel", "محطة وقود", "Fuel", FUEL, 18),
    ("ev_charging", "شحن سيارات", "EV charging", FUEL, 10),
    ("car_wash", "مغسلة سيارات", "Car wash", TRANSPORT, 6),
    ("car_repair", "ورشة سيارات", "Car repair", TRANSPORT, 4),
    ("oil_change", "غيار زيت", "Oil change", TRANSPORT, 4),
    ("tires", "كفرات", "Tyres", TRANSPORT, 4),
    ("car_parts", "قطع غيار", "Car parts", TRANSPORT, 0),
    ("car_dealer", "معرض سيارات", "Car dealer", TRANSPORT, 0),
    ("car_rental", "تأجير سيارات", "Car rental", TRANSPORT, 2),
    ("parking", "موقف سيارات", "Parking", TRANSPORT, -6),
    ("rest_area", "استراحة طريق", "Rest area", TRANSPORT, 10),
    ("driving_school", "مدرسة قيادة", "Driving school", TRANSPORT, 0),
    ("taxi", "تاكسي", "Taxi", TRANSPORT, -2),
    ("grocery", "بقالة", "Grocery", SHOP, 4),
    ("supermarket", "سوبرماركت", "Supermarket", SHOP, 14),
    ("mall", "مول", "Mall", SHOP, 24),
    ("market", "سوق", "Market", SHOP, 8),
    ("dept_store", "متجر كبير", "Department store", SHOP, 8),
    ("vegetables", "خضار وفواكه", "Greengrocer", SHOP, 0),
    ("butcher", "ملحمة", "Butcher", SHOP, 0),
    ("fish_market", "أسماك", "Fish market", SHOP, 0),
    ("clothes", "ملابس", "Clothes", SHOP, 0),
    ("tailor", "خياط", "Tailor", SHOP, -2),
    ("shoes", "أحذية", "Shoes", SHOP, 0),
    ("jewelry", "ذهب ومجوهرات", "Jewellery", SHOP, 0),
    ("optician", "نظارات", "Optician", SHOP, 0),
    ("perfume", "عطور", "Perfume", SHOP, 0),
    ("electronics", "إلكترونيات", "Electronics", SHOP, 2),
    ("mobile", "جوالات", "Mobile phones", SHOP, 0),
    ("furniture", "أثاث", "Furniture", SHOP, 0),
    ("hardware", "مواد بناء", "Hardware", SHOP, 0),
    ("flowers", "ورد وهدايا", "Flowers and gifts", SHOP, -2),
    ("books", "مكتبة", "Books and stationery", SHOP, 2),
    ("toys", "ألعاب", "Toys", SHOP, -2),
    ("sports_shop", "مستلزمات رياضية", "Sports shop", SHOP, -2),
    ("pets", "حيوانات أليفة", "Pets", SHOP, -2),
    ("shop", "متجر", "Shop", SHOP, -4),
    ("hospital", "مستشفى", "Hospital", HEALTH, 28),
    ("clinic", "عيادة", "Clinic", HEALTH, 8),
    ("dentist", "طب أسنان", "Dentist", HEALTH, 4),
    ("pharmacy", "صيدلية", "Pharmacy", HEALTH, 14),
    ("lab", "مختبر", "Medical lab", HEALTH, 0),
    ("vet", "بيطري", "Vet", HEALTH, 0),
    ("mosque", "مسجد", "Mosque", WORSHIP, 10),
    ("hotel", "فندق", "Hotel", LODGING, 14),
    ("apartments", "شقق مفروشة", "Furnished apartments", LODGING, 4),
    ("resort", "منتجع وشاليهات", "Resort", LODGING, 4),
    ("bank", "بنك", "Bank", BANK, 12),
    ("atm", "صراف", "ATM", BANK, 6),
    ("exchange", "صرافة وتحويل", "Exchange", BANK, 2),
    ("school", "مدرسة", "School", SCHOOL, 4),
    ("kindergarten", "روضة", "Kindergarten", SCHOOL, -2),
    ("university", "جامعة", "University", SCHOOL, 24),
    ("institute", "معهد", "Institute", SCHOOL, 0),
    ("library", "مكتبة عامة", "Library", SCHOOL, 2),
    ("gov", "جهة حكومية", "Government office", PUBLIC, 8),
    ("municipality", "بلدية", "Municipality", PUBLIC, 12),
    ("police", "شرطة", "Police", PUBLIC, 12),
    ("civil_defense", "دفاع مدني", "Civil defence", PUBLIC, 8),
    ("court", "محكمة", "Court", PUBLIC, 8),
    ("post", "بريد", "Post office", PUBLIC, 6),
    ("shipping", "شحن", "Shipping", PUBLIC, 0),
    ("embassy", "سفارة", "Embassy", PUBLIC, 6),
    ("community", "جمعية", "Community", PUBLIC, -2),
    ("airport", "مطار", "Airport", TRANSPORT, 40),
    ("bus", "محطة باص", "Bus station", TRANSPORT, 14),
    ("train", "محطة قطار", "Train station", TRANSPORT, 24),
    ("port", "ميناء", "Port", TRANSPORT, 14),
    ("park", "حديقة", "Park", PARK, 12),
    ("beach", "شاطئ", "Beach", PARK, 12),
    ("gym", "نادي رياضي", "Gym", PARK, 0),
    ("sports_venue", "ملعب", "Sports ground", PARK, 8),
    ("cinema", "سينما", "Cinema", SIGHT, 10),
    ("fun", "ترفيه", "Entertainment", SIGHT, 8),
    ("events", "قاعة مناسبات", "Event hall", SIGHT, -2),
    ("museum", "متحف", "Museum", SIGHT, 14),
    ("historic", "موقع تاريخي", "Historic site", SIGHT, 8),
    ("nature", "معلم طبيعي", "Natural feature", SIGHT, 4),
    ("barber", "حلاق", "Barber", OTHER, -2),
    ("salon", "صالون", "Salon", OTHER, -2),
    ("laundry", "مغسلة ملابس", "Laundry", OTHER, 0),
    ("travel", "سفر وسياحة", "Travel", OTHER, -2),
    ("real_estate", "عقار", "Real estate", OTHER, -8),
    ("office", "مكتب", "Office", OTHER, -8),
    ("factory", "مصنع", "Factory", OTHER, -6),
    ("industrial", "منطقة صناعية", "Industrial area", OTHER, 8),
    ("farm", "مزرعة", "Farm", OTHER, -6),
    ("building", "مبنى", "Building", OTHER, -2),
    ("cemetery", "مقبرة", "Cemetery", OTHER, 0),
    ("military", "موقع عسكري", "Military site", OTHER, -10),
    ("exit", "مخرج", "Motorway exit", TRANSPORT, 4),
    ("road", "طريق", "Road", OTHER, 0),
    ("roundabout", "دوار", "Roundabout", OTHER, 0),
    ("bridge", "جسر", "Bridge", OTHER, 0),
    ("tunnel", "نفق", "Tunnel", OTHER, 0),
    ("city", "مدينة", "City", OTHER, 0),
    ("neighbourhood", "حي", "District", OTHER, 0),
    ("region", "منطقة", "Region", OTHER, 0),
    ("other", "مكان", "Place", OTHER, -6),
]

# Overture taxonomy word -> category key. The row's own taxonomy word is looked up first, then its parents from the
# most specific up, then basic_category.
_OV = {
    "restaurant": "restaurant food_and_drink food_service food_delivery_service food_court diner buffet_restaurant salad_bar",
    "fast_food": "fast_food_restaurant sandwich_shop food_truck_stand casual_eatery delicatessen",
    "pizza": "pizza_restaurant pizza_delivery_service",
    "burger": "burger_restaurant",
    "chicken": "chicken_restaurant",
    "seafood": "seafood_restaurant fish_and_chips_restaurant",
    "breakfast": "breakfast_and_brunch_restaurant",
    "bakery": "bakery bagel_shop",
    "sweets": "dessert_shop ice_cream_shop donut_shop cupcake_shop candy_store chocolatier frozen_yogurt_shop",
    "juice": "smoothie_juice_bar bubble_tea_shop",
    "coffee": "coffee_shop coffee_roastery cafe",
    "tea": "tea_room non_alcoholic_beverage_venue",
    "lounge": "bar hookah_bar lounge airport_lounge alcoholic_beverage_venue nightlife_venue dance_club",
    "fuel": "gas_station fueling_station",
    "ev_charging": "ev_charging_station",
    "car_wash": "car_wash auto_detailing",
    "car_repair": "automotive_repair automotive_service vehicle_service",
    "oil_change": "oil_change_station",
    "tires": "tire_dealer_and_repair tire_shop",
    "car_parts": "auto_parts_store vehicle_parts_store",
    "car_dealer": "auto_dealer vehicle_dealer motorcycle_dealer motorsport_vehicle_dealer auto_company",
    "car_rental": "car_rental_service vehicle_rental_service",
    "parking": "parking",
    "driving_school": "driving_school",
    "taxi": "taxi_service taxi_or_ride_share_service",
    "grocery": "grocery_store organic_grocery_store convenience_store food_and_beverage_store health_food_store specialty_foods_store",
    "supermarket": "superstore warehouse_club_store",
    "mall": "shopping_mall",
    "market": "market farmers_market flea_market second_hand_store antique_store",
    "dept_store": "department_store discount_store outlet_store",
    "vegetables": "produce_store",
    "butcher": "butcher_shop meat_wholesaler",
    "fish_market": "fishmonger",
    "clothes": "fashion_and_apparel_store clothing_store fashion_boutique womens_clothing_store mens_clothing_store childrens_clothing_store "
               "lingerie_store designer_clothing uniform_store bridal_shop fashion_accessories_store fabric_store baby_gear_and_nursery_store luggage_store",
    "tailor": "tailor sewing_and_alterations",
    "shoes": "shoe_store",
    "jewelry": "jewelry_store watch_store",
    "optician": "eyewear_store",
    "perfume": "cosmetics_and_fragrance_store beauty_supply_store personal_care_and_beauty_store hair_supply_store",
    "electronics": "electronics_store computer_store camera_and_photography_store",
    "mobile": "mobile_phone_store",
    "furniture": "furniture_store home_goods_store mattress_store carpet_store linen_store lighting_store",
    "hardware": "hardware_home_and_garden_store hardware_store paint_store building_supply_store home_improvement_store",
    "flowers": "florist flowers_and_gifts_store gift_shop nursery_and_gardening_store party_supply_store",
    "books": "bookstore books_music_and_video_store office_supply_store music_and_dvd_store",
    "toys": "toy_store toys_and_games_store video_game_store",
    "sports_shop": "sporting_goods_store sportswear_store bike_store outdoor_store hunting_and_fishing_store",
    "pets": "pet_store animal_and_pet_store",
    "shop": "shopping specialty_store arts_crafts_and_hobby_store tobacco_shop medical_supply_store vitamin_and_supplement_store",
    "hospital": "hospital emergency_department emergency_or_urgent_care_facility",
    "clinic": "outpatient_care_facility health_care medical_service doctors_office specialized_health_care specialized_medical_facility "
              "primary_care_or_general_clinic walk_in_clinic surgery physical_medicine_and_rehabilitation reproductive_perinatal_and_womens_care "
              "behavioral_or_mental_health_clinic vision_or_eye_care_clinic pediatric_clinic complementary_and_alternative_medicine",
    "dentist": "dental_clinic cosmetic_dentistry",
    "pharmacy": "pharmacy pharmacy_and_drug_store",
    "lab": "diagnostics_imaging_or_lab_service laboratory_testing",
    "vet": "veterinarian veterinary_care animal_or_pet_service",
    "mosque": "muslim_place_of_worship place_of_worship",
    "hotel": "hotel bed_and_breakfast motel",
    "apartments": "lodging private_lodging holiday_rental_home service_apartment apartment hostel condominium",
    "resort": "resort cottage rv_park campground",
    "bank": "bank bank_or_credit_union",
    "atm": "atm",
    "exchange": "currency_exchange money_transfer_service",
    "school": "school elementary_school middle_school high_school private_school public_school place_of_learning",
    "kindergarten": "preschool day_care_preschool child_care_and_day_care family_service",
    "university": "college_university campus_building medical_school research_institute",
    "institute": "specialty_school language_school vocational_and_technical_school computer_coaching educational_service tutoring_service education educational_facility",
    "library": "library",
    "gov": "government_office government_department community_and_government public_utility electric_utility_provider water_utility_provider",
    "municipality": "town_hall",
    "police": "police_station",
    "civil_defense": "fire_station",
    "court": "courthouse",
    "post": "post_office",
    "shipping": "shipping_center shipping_or_delivery_service freight_and_cargo_service",
    "embassy": "embassy",
    "community": "social_or_community_service community_center charity_organization civic_organization non_governmental_association religious_organization political_organization labor_union",
    "airport": "airport airport_terminal",
    "bus": "bus_station public_transit_facility_or_service",
    "train": "train_station rail_facility_or_service",
    "port": "pier marina",
    "park": "park garden botanical_garden national_park playground public_plaza nature_reserve dog_park",
    "beach": "beach",
    "gym": "gym fitness_studio pilates_studio sport_or_fitness_facility fitness_trainer sport_or_recreation_club sport_league sports_and_recreation sport_team",
    "sports_venue": "stadium_arena sport_field soccer_field sport_court tennis_court basketball_court race_track golf_course skating_rink swimming_pool bowling_alley",
    "cinema": "movie_theater",
    "fun": "amusement_park arcade gaming_venue water_park zoo aquarium arts_and_entertainment skate_park animal_attraction science_attraction pool_billiards",
    "events": "event_venue auditorium music_venue event_or_party_service party_and_event_planning wedding_planning fairgrounds caterer theatre_venue performing_arts_venue",
    "museum": "museum history_museum art_gallery cultural_center planetarium",
    "historic": "historic_site castle monument palace fort sculpture_statue cultural_and_historic memorial_site",
    "nature": "mountain lake river land_feature geographic_entities forest hot_springs waterfall canal water_feature",
    "bridge": "bridge",
    "barber": "barber",
    "salon": "beauty_salon hair_salon nail_salon spa personal_or_beauty_service wellness_service massage_therapy skin_care_and_makeup",
    "laundry": "laundry_service laundromat dry_cleaning",
    "travel": "travel_service travel_agent tour_operator travel_company travel_and_transportation passport_and_visa_service air_transport_facility_or_service airline",
    "real_estate": "real_estate_service real_estate_agent home_developer property_management commercial_real_estate real_estate_investment housing_or_property_service",
    "office": "professional_service corporate_or_business_office b2b_service home_service building_or_construction_service technical_service design_service "
              "media_service financial_service insurance_agency investing financial_advising accountant legal_service attorney_or_law_firm "
              "services_and_business lifestyle_services rental_service telecommunications_service printing_service environmental_or_ecological_service",
    "factory": "manufacturer commercial_industrial b2b_industrial_and_machine_service industrial_facility_or_service industrial_company storage_facility supplier_or_distributor wholesaler",
    "farm": "farm agricultural_service agriculture",
    "military": "military_site military_base jail_or_prison",
}
OVERTURE = {}
for _k, _v in _OV.items():
    for _w in _v.split():
        OVERTURE[_w] = _k


def overture_category(primary, hierarchy, basic):
    """Category key of an Overture row: its own taxonomy word, then its parents from the most specific up."""
    if primary in OVERTURE:
        return OVERTURE[primary]
    for w in reversed(hierarchy or []):
        if w in OVERTURE:
            return OVERTURE[w]
    if basic in OVERTURE:
        return OVERTURE[basic]
    return "other"


_AMENITY = {
    "restaurant": "restaurant", "fast_food": "fast_food", "food_court": "restaurant", "cafe": "coffee", "ice_cream": "sweets", "bar": "lounge", "pub": "lounge",
    "hookah_lounge": "lounge", "nightclub": "lounge", "fuel": "fuel", "charging_station": "ev_charging", "car_wash": "car_wash", "car_rental": "car_rental",
    "vehicle_inspection": "car_repair", "parking": "parking", "bus_station": "bus", "taxi": "taxi", "ferry_terminal": "port", "hospital": "hospital",
    "clinic": "clinic", "doctors": "clinic", "dentist": "dentist", "pharmacy": "pharmacy", "veterinary": "vet", "bank": "bank", "atm": "atm",
    "bureau_de_change": "exchange", "money_transfer": "exchange", "school": "school", "kindergarten": "kindergarten", "childcare": "kindergarten",
    "college": "institute", "university": "university", "language_school": "institute", "driving_school": "driving_school", "music_school": "institute",
    "library": "library", "townhall": "municipality", "police": "police", "fire_station": "civil_defense", "courthouse": "court", "post_office": "post",
    "embassy": "embassy", "community_centre": "community", "social_facility": "community", "social_centre": "community", "cinema": "cinema", "theatre": "events",
    "arts_centre": "museum", "events_venue": "events", "conference_centre": "events", "marketplace": "market", "public_building": "gov", "government": "gov",
    "prison": "military", "grave_yard": "cemetery", "exhibition_centre": "events", "stables": "fun",
}
_SHOP = {
    "supermarket": "supermarket", "wholesale": "supermarket", "convenience": "grocery", "general": "grocery", "food": "grocery", "greengrocer": "vegetables",
    "butcher": "butcher", "seafood": "fish_market", "bakery": "bakery", "pastry": "sweets", "confectionery": "sweets", "chocolate": "sweets", "ice_cream": "sweets",
    "coffee": "coffee", "tea": "tea", "mall": "mall", "department_store": "dept_store", "variety_store": "dept_store", "clothes": "clothes", "fashion": "clothes",
    "boutique": "clothes", "fabric": "clothes", "bag": "clothes", "baby_goods": "clothes", "tailor": "tailor", "shoes": "shoes", "jewelry": "jewelry", "gold": "jewelry",
    "watches": "jewelry", "optician": "optician", "perfumery": "perfume", "cosmetics": "perfume", "beauty": "salon", "hairdresser": "barber", "electronics": "electronics",
    "computer": "electronics", "appliance": "electronics", "hifi": "electronics", "mobile_phone": "mobile", "furniture": "furniture", "carpet": "furniture", "bed": "furniture",
    "houseware": "furniture", "curtain": "furniture", "kitchen": "furniture", "interior_decoration": "furniture", "lighting": "furniture", "hardware": "hardware",
    "doityourself": "hardware", "paint": "hardware", "trade": "hardware", "building_materials": "hardware", "florist": "flowers", "gift": "flowers", "garden_centre": "flowers",
    "books": "books", "stationery": "books", "toys": "toys", "video_games": "toys", "sports": "sports_shop", "bicycle": "sports_shop", "outdoor": "sports_shop", "pet": "pets",
    "laundry": "laundry", "dry_cleaning": "laundry", "travel_agency": "travel", "car_repair": "car_repair", "car_parts": "car_parts", "tyres": "tires", "car": "car_dealer",
    "motorcycle": "car_dealer", "truck": "car_dealer", "car_wash": "car_wash", "oil_change": "oil_change", "estate_agent": "real_estate", "money_lender": "exchange",
}
_TOURISM = {
    "hotel": "hotel", "motel": "hotel", "guest_house": "hotel", "apartment": "apartments", "hostel": "apartments", "chalet": "resort", "camp_site": "resort",
    "caravan_site": "resort", "wilderness_hut": "resort", "museum": "museum", "gallery": "museum", "zoo": "fun", "aquarium": "fun", "theme_park": "fun",
    "attraction": "historic", "viewpoint": "nature", "artwork": "historic",
}
_LEISURE = {
    "park": "park", "garden": "park", "playground": "park", "nature_reserve": "park", "dog_park": "park", "common": "park", "fitness_centre": "gym", "sports_centre": "gym",
    "fitness_station": "gym", "stadium": "sports_venue", "pitch": "sports_venue", "track": "sports_venue", "swimming_pool": "sports_venue", "golf_course": "sports_venue",
    "ice_rink": "sports_venue", "bowling_alley": "sports_venue", "beach_resort": "resort", "resort": "resort", "water_park": "fun", "amusement_arcade": "fun",
    "miniature_golf": "fun", "adult_gaming_centre": "fun", "marina": "port", "horse_riding": "gym", "sports_hall": "gym",
}
_HEALTHCARE = {
    "hospital": "hospital", "clinic": "clinic", "doctor": "clinic", "centre": "clinic", "dentist": "dentist", "pharmacy": "pharmacy", "laboratory": "lab",
    "rehabilitation": "clinic", "physiotherapist": "clinic", "optometrist": "optician", "alternative": "clinic", "blood_donation": "clinic", "dialysis": "clinic",
}
_OFFICE = {"government": "gov", "estate_agent": "real_estate", "travel_agent": "travel", "educational_institution": "institute", "ngo": "community", "association": "community",
           "diplomatic": "embassy", "telecommunication": "office"}
# unknown shop or amenity values and places with a real name stay findable, as these
_CUISINE = [("pizza", "pizza"), ("burger", "burger"), ("chicken", "chicken"), ("broast", "chicken"), ("seafood", "seafood"), ("fish", "seafood"), ("breakfast", "breakfast"),
            ("coffee", "coffee"), ("juice", "juice"), ("ice_cream", "sweets"), ("dessert", "sweets"), ("sweets", "sweets")]


def osm_category(t):
    """Category key of an OpenStreetMap object, or None when it is not something to list."""
    am = t.get("amenity")
    if am:
        if am == "place_of_worship":
            r = t.get("religion", "muslim")
            return "mosque" if r in ("muslim", "islam") else "other"
        if am in ("restaurant", "fast_food", "cafe"):
            cu = t.get("cuisine", "")
            for k, v in _CUISINE:
                if k in cu:
                    return v
        if am in _AMENITY:
            return _AMENITY[am]
    sh = t.get("shop")
    if sh:
        return _SHOP.get(sh, "shop")
    hc = t.get("healthcare")
    if hc:
        return _HEALTHCARE.get(hc, "clinic")
    to = t.get("tourism")
    if to and to in _TOURISM:
        return _TOURISM[to]
    le = t.get("leisure")
    if le and le in _LEISURE:
        return _LEISURE[le]
    ae = t.get("aeroway")
    if ae in ("aerodrome", "terminal"):
        return "airport"
    if t.get("railway") in ("station", "halt"):
        return "train"
    pt = t.get("public_transport")
    if pt == "station":
        return "bus"
    hw = t.get("highway")
    if hw in ("services", "rest_area"):
        return "rest_area"
    if hw == "motorway_junction":
        return "exit"
    of = t.get("office")
    if of:
        return _OFFICE.get(of, "office")
    if t.get("craft"):
        return "office"
    if t.get("historic"):
        return "historic"
    if t.get("emergency") == "ambulance_station":
        return "civil_defense"
    if t.get("military"):
        return "military"
    na = t.get("natural")
    if na == "beach":
        return "beach"
    if na in ("peak", "volcano", "cape", "bay", "wadi", "valley", "hill", "spring", "cave_entrance", "wetland", "oasis", "sand", "desert"):
        return "nature"
    mm = t.get("man_made")
    if mm in ("works", "storage_tank", "wastewater_plant", "water_works", "petroleum_well", "pumping_station"):
        return "factory"
    if mm in ("pier", "breakwater"):
        return "port"
    if mm in ("tower", "lighthouse", "bridge", "minaret"):
        return "building"
    lu = t.get("landuse")
    if lu == "industrial":
        return "industrial"
    if lu == "cemetery":
        return "cemetery"
    if lu in ("retail", "commercial"):
        return "market" if lu == "retail" else "office"
    if lu == "military":
        return "military"
    if lu in ("farmland", "farmyard", "orchard"):
        return "farm"
    if t.get("building"):
        return "building"
    if am or to or le:
        return "other"
    return None


_RESTAURANTS = ["restaurant", "fast_food", "pizza", "burger", "chicken", "seafood", "breakfast"]

# What people type or say, to categories: (single words, phrases, categories). Saudi and Gulf speech first, spelling
# variants as people type them. A word in several lines means all their categories.
SYNONYMS = [
    # food
    ("مطعم مطاعم اكل أكل غداء عشاء وجبات restaurant restaurants food eat", [], _RESTAURANTS),
    ("", ["وجبات سريعة", "فاست فود", "fast food"], ["fast_food", "burger", "chicken", "pizza"]),
    ("برجر برقر بيرجر همبرجر burger burgers", [], ["burger", "fast_food"]),
    ("بيتزا pizza", [], ["pizza"]),
    ("بروست دجاج فراخ broast chicken", [], ["chicken"]),
    ("شاورما شورما شاورمة shawarma", [], ["fast_food", "restaurant"]),
    ("مشويات مشاوي كباب مندي مظبي كبسة grill", [], ["restaurant"]),
    ("سمك أسماك سماك سمكة بحري seafood fish", ["مأكولات بحرية"], ["seafood", "fish_market"]),
    ("فطور فوال تميس فول breakfast", [], ["breakfast"]),
    ("مخبز مخابز خبز فرن bakery", [], ["bakery"]),
    ("حلويات حلا كيك دونات ايسكريم بوظة sweets dessert desserts cake", ["آيس كريم", "ice cream"], ["sweets"]),
    ("عصير عصيرات juice", [], ["juice"]),
    # coffee, in every way it is said here
    ("قهوة قهوه گهوة جهوة كهوة كوفي كوفيهات كوفيات كافيه كافيهات كافي كوفيشوب coffee cafe cafes kahwa gahwa qahwa", ["كوفي شوب", "coffee shop"], ["coffee", "lounge"]),
    ("مقهى مقاهي قهاوي", [], ["coffee", "lounge", "tea"]),
    ("شاي كرك tea karak", [], ["tea", "coffee"]),
    ("معسل شيشة lounge hookah shisha", [], ["lounge"]),
    # fuel and the car
    ("بنزين بنزينة بترول محطة محطات وقود ديزل gas petrol fuel", ["محطة بنزين", "محطة وقود", "محطات وقود", "محطة بترول", "gas station", "petrol station", "fuel station"], ["fuel"]),
    ("شاحن", ["شحن كهربائي", "شحن سيارات", "ev charging", "ev charger", "charging station"], ["ev_charging"]),
    ("تلميع carwash", ["مغسلة سيارات", "مغاسل سيارات", "غسيل سيارات", "مغسلة سياره", "car wash"], ["car_wash"]),
    ("مغسلة مغاسل غسيل", [], ["car_wash", "laundry"]),
    ("كوي laundry", ["مغسلة ملابس", "مغاسل ملابس", "غسيل ملابس", "غسيل وكوي", "dry cleaning", "dry cleaner"], ["laundry"]),
    ("ورشة ورش ورشه ميكانيكي سمكري garage workshop mechanic", ["ورشة سيارات", "صيانة سيارات", "كهربائي سيارات", "car repair"], ["car_repair", "oil_change", "tires"]),
    ("بنشر بناشر بنشري كفرات كفر تواير إطارات اطارات اطار tyre tyres tire tires", [], ["tires"]),
    ("زيتي زيت زيوت lube", ["تغيير زيت", "غيار زيت", "تبديل زيت", "oil change"], ["oil_change"]),
    ("سكراب تشليح", ["قطع غيار", "spare parts", "auto parts", "car parts"], ["car_parts"]),
    ("وكالة showroom", ["معرض سيارات", "معارض سيارات", "وكالة سيارات", "car dealer"], ["car_dealer"]),
    ("", ["تأجير سيارات", "ايجار سيارات", "إيجار سيارات", "تاجير سيارات", "car rental", "rent a car"], ["car_rental"]),
    ("موقف مواقف باركنق parking", ["موقف سيارات"], ["parking"]),
    ("استراحة استراحات", ["rest area", "rest stop"], ["rest_area", "resort"]),
    ("", ["مدرسة قيادة", "تعليم قيادة", "driving school"], ["driving_school"]),
    ("تاكسي taxi", [], ["taxi"]),
    # shops
    ("بقالة بقاله بقالات تموينات دكان grocery convenience", ["مواد غذائية"], ["grocery", "supermarket"]),
    ("سوبرماركت هايبر هايبرماركت ماركت supermarket hypermarket", ["سوبر ماركت", "هايبر ماركت"], ["supermarket", "grocery"]),
    ("مول مولات mall malls", ["مجمع تجاري", "مجمعات تجارية", "shopping center", "shopping centre"], ["mall"]),
    ("سوق أسواق souq souk market", [], ["market", "supermarket", "grocery"]),
    ("خضار خضروات فواكه vegetables", ["خضار وفواكه"], ["vegetables"]),
    ("ملحمة ملاحم لحوم جزار butcher", [], ["butcher"]),
    ("ملابس عبايات عباية ازياء clothes fashion", [], ["clothes"]),
    ("خياط خياطة خياطين tailor", [], ["tailor"]),
    ("أحذية جزم shoes", [], ["shoes"]),
    ("ذهب مجوهرات jewelry jewellery gold", [], ["jewelry"]),
    ("نظارات بصريات optician glasses optical", [], ["optician"]),
    ("عطور عطر عطارة عطار عود بخور مكياج perfume perfumes oud cosmetics", [], ["perfume"]),
    ("جوال جوالات موبايل phones mobile", [], ["mobile", "electronics"]),
    ("إلكترونيات الكترونيات كمبيوتر حاسب electronics computer", [], ["electronics", "mobile"]),
    ("أثاث اثاث مفروشات موبيليا سجاد furniture carpet", [], ["furniture"]),
    ("سباكة دهانات عدد hardware paint", ["مواد بناء", "أدوات صحية"], ["hardware"]),
    ("ورد زهور هدايا flowers gifts florist", [], ["flowers"]),
    ("مكتبة قرطاسية مكتبات books bookstore stationery library", [], ["books", "library"]),
    ("ألعاب العاب toys", [], ["toys", "fun"]),
    ("حيوانات pets", ["حيوانات اليفة"], ["pets", "vet"]),
    # health
    ("مستشفى مستشفيات طوارئ hospital hospitals emergency", [], ["hospital"]),
    ("مستوصف مستوصفات عيادة عيادات clinic clinics medical doctor", ["مجمع طبي", "مركز طبي", "مركز صحي"], ["clinic", "hospital"]),
    ("أسنان اسنان dentist dental", ["دكتور اسنان", "طبيب اسنان"], ["dentist"]),
    ("صيدلية صيدليه صيدليات صيدلة دواء أدوية pharmacy pharmacies pharma drugstore", [], ["pharmacy"]),
    ("مختبر مختبرات تحاليل lab laboratory", [], ["lab"]),
    ("بيطري vet veterinary", [], ["vet"]),
    # worship
    ("مسجد مساجد جامع جوامع صلاة mosque masjid mosques", ["وين اصلي", "ابي اصلي", "ابغى اصلي"], ["mosque"]),
    # rooms
    ("فندق فنادق hotel hotels lodging", [], ["hotel", "apartments"]),
    ("شقق apartments", ["شقق مفروشة"], ["apartments"]),
    ("شاليه شاليهات منتجع منتجعات resort chalet", [], ["resort"]),
    # money
    ("بنك بنوك مصرف bank banks", [], ["bank"]),
    ("صراف صرافات سحب atm cash", ["صراف آلي"], ["atm", "bank"]),
    ("صرافة تحويل exchange", ["تحويل اموال", "money transfer"], ["exchange"]),
    # learning
    ("مدرسة مدارس school schools", [], ["school"]),
    ("روضة حضانة kindergarten nursery", [], ["kindergarten"]),
    ("جامعة جامعات كلية كليات university college", [], ["university"]),
    ("معهد معاهد institute", [], ["institute"]),
    # the state
    ("بلدية أمانة municipality", [], ["municipality"]),
    ("شرطة مرور police", [], ["police"]),
    ("اطفاء إطفاء", ["دفاع مدني", "fire station", "civil defense"], ["civil_defense"]),
    ("محكمة court", [], ["court"]),
    ("بريد post", [], ["post"]),
    ("شحن سمسا ارامكس shipping courier", [], ["shipping"]),
    ("سفارة قنصلية embassy consulate", [], ["embassy"]),
    ("جمعية charity", [], ["community"]),
    ("حكومي government", ["جهة حكومية"], ["gov"]),
    # moving
    ("مطار airport airports", [], ["airport"]),
    ("باص باصات سابتكو bus", ["نقل جماعي"], ["bus"]),
    ("قطار قطارات مترو train metro railway", ["سكة حديد"], ["train"]),
    ("ميناء مرسى port marina harbour", [], ["port"]),
    # out and about
    ("حديقة حدائق منتزه منتزهات بارك park parks garden", [], ["park"]),
    ("شاطئ شاطي بحر كورنيش beach corniche", [], ["beach", "park"]),
    ("نادي جيم gym fitness club", ["نادي رياضي"], ["gym"]),
    ("ملعب ملاعب استاد stadium", [], ["sports_venue"]),
    ("سينما cinema movies", [], ["cinema"]),
    ("ترفيه ملاهي entertainment", [], ["fun"]),
    ("قاعة قاعات", ["قصر أفراح", "قصور أفراح", "قاعة أفراح", "wedding hall"], ["events"]),
    ("متحف متاحف museum", [], ["museum"]),
    ("آثار قلعة historic castle", ["موقع أثري"], ["historic"]),
    ("حلاق حلاقة حلاقين barber", [], ["barber"]),
    ("صالون مشغل كوافير salon spa", [], ["salon"]),
    ("سفر سياحة سفريات travel", [], ["travel"]),
    ("عقار عقارات", ["real estate"], ["real_estate"]),
    ("مصنع مصانع factory", [], ["factory"]),
    ("صناعية صناعيه industrial", ["منطقة صناعية", "المدينة الصناعية"], ["industrial"]),
    ("مزرعة مزارع farm", [], ["farm"]),
    ("مقبرة مقابر cemetery", [], ["cemetery"]),
    ("مخرج exit", [], ["exit"]),
    ("دوار دوارات دوّار roundabout", [], ["roundabout"]),
    ("مبنى برج أبراج tower", [], ["building"]),
    # more of how it is said here
    ("بوفيه بوفية بوفيات كافتيريا كفتيريا كافيتريا cafeteria", [], ["fast_food", "restaurant", "breakfast"]),
    ("مطبخ مطابخ", [], ["restaurant"]),
    ("دواجن poultry", [], ["chicken", "butcher"]),
    ("تمور تمر dates", [], ["sweets", "grocery"]),
    ("جوازات إمارة امارة وزارة passports", ["احوال مدنيه", "الأحوال المدنية"], ["gov"]),
    ("ممشى ممشي walkway", [], ["park", "beach"]),
    ("حراج", [], ["market", "car_dealer"]),
    ("مكيفات تكييف", [], ["electronics", "hardware"]),
    ("كهربائيات سباك نجارة حدادة زجاج", [], ["hardware"]),
    ("مطبعة طباعة printing", [], ["office", "books"]),
    ("محامي محاماة lawyer", [], ["office"]),
    ("سطحة سطحات", [], ["car_repair"]),
]

# Words for one thing within a kind: the category is only a fallback for these, a place with the word in its name comes first (role 5, see syn).
# "شاورما" should not answer KFC while a shawarma shop is near.
SPECIFIC = """دواء أدوية شاورما شورما شاورمة shawarma مشويات مشاوي كباب مندي مظبي كبسة grill بروست broast فوال فول تميس كرك karak شيشة معسل hookah shisha
    عود بخور oud سمسا ارامكس سابتكو كورنيش corniche ممشى ممشي walkway بوفيه بوفية بوفيات كافتيريا كفتيريا كافيتريا cafeteria تمور تمر dates مطبخ مطابخ دواجن poultry
    جوازات إمارة امارة وزارة passports حراج مكيفات تكييف كهربائيات سباك نجارة حدادة زجاج مطبعة طباعة printing محامي محاماة lawyer سطحة سطحات عسل
    عبايات عباية سجاد carpet كمبيوتر حاسب computer سكراب تشليح زيتي""".split()

# Words that are never required. role: 1 road word (a hint that a road is meant), 2 optional word (titles, company words),
# 3 stop word (dropped), 4 district word (a hint). canon: the one spelling compared when ranking, never shown.
# Roles 6, 7 and 8 are below: FILLERS, LETTERS, ALIASES.
ROLES = {
    "محل": (2, "محل"), "محلات": (2, "محل"), "متجر": (2, "محل"), "shop": (2, "محل"), "store": (2, "محل"), "مركز": (2, "مركز"), "center": (2, "مركز"), "centre": (2, "مركز"), "مجمع": (2, "مجمع"),
    "شارع": (1, "طريق"), "طريق": (1, "طريق"), "street": (1, "طريق"), "st": (1, "طريق"), "road": (1, "طريق"), "rd": (1, "طريق"), "جادة": (1, "طريق"),
    "highway": (1, "طريق"), "expressway": (1, "طريق"), "hwy": (1, "طريق"), "avenue": (1, "طريق"), "ave": (1, "طريق"),
    "جسر": (1, "جسر"), "كوبري": (1, "جسر"), "bridge": (1, "جسر"), "نفق": (1, "نفق"), "tunnel": (1, "نفق"),
    "حي": (4, "حي"), "district": (4, "حي"), "dist": (4, "حي"), "neighborhood": (4, "حي"), "neighbourhood": (4, "حي"),
    "ملك": (2, "ملك"), "king": (2, "ملك"), "امير": (2, "امير"), "prince": (2, "امير"), "شيخ": (2, "شيخ"), "sheikh": (2, "شيخ"), "shaikh": (2, "شيخ"), "sheik": (2, "شيخ"),
    "ملكه": (2, "ملكه"), "queen": (2, "ملكه"), "اميره": (2, "اميره"), "princess": (2, "اميره"),
    "شركه": (2, "شركه"), "company": (2, "شركه"), "co": (2, "شركه"), "ltd": (2, "شركه"), "llc": (2, "شركه"), "مؤسسه": (2, "مؤسسه"), "est": (2, "مؤسسه"), "establishment": (2, "مؤسسه"),
    "مجموعه": (2, "مجموعه"), "group": (2, "مجموعه"), "فرع": (2, "فرع"), "branch": (2, "فرع"), "مدينه": (2, "مدينه"), "city": (2, "مدينه"), "منطقه": (2, "منطقه"),
    "region": (2, "منطقه"), "province": (2, "منطقه"), "محافظه": (2, "محافظه"), "governorate": (2, "محافظه"),
    "بن": (3, "بن"), "ابن": (3, "بن"), "bin": (3, "بن"), "ibn": (3, "بن"), "bn": (3, "بن"), "ال": (3, ""), "al": (3, ""), "el": (3, ""), "the": (3, ""), "of": (3, ""), "and": (3, ""),
    "و": (3, ""), "في": (3, ""), "من": (3, ""), "الي": (3, ""), "ar": (3, ""), "as": (3, ""), "ad": (3, ""), "at": (3, ""), "an": (3, ""), "ash": (3, ""), "az": (3, ""), "ath": (3, ""), "adh": (3, ""),
    "اقرب": (3, ""), "nearest": (3, ""), "near": (3, ""), "قريب": (3, ""), "قريبه": (3, ""), "ودني": (3, ""), "وديني": (3, ""), "قريبين": (3, ""), "لاقرب": (3, ""),
    "عام": (2, "عام"), "عامه": (2, "عام"), "general": (2, "عام"),
    # (an airport's sign says it, and the driver leaves it out: مطار الملك خالد is the whole name of مطار الملك خالد الدولي)
    "دولي": (2, "دولي"), "international": (2, "دولي"),
}

# Role 6, what people say around what they want: "وين اقرب صيدلية", "ابغى اروح الراشد مول", "خذني لاقرب محطة", "ابي قهوة". At the start or the end of a query these are only speech:
# never required, but rows that hold them too come first ("روح الشرق", "ابي طالب"), because the search also reads the rows with every word. In the middle of a query they are plain words.
# Not stop words: a name may hold them, and the index keeps them.
FILLERS = """وين فين ابي ابى ابغى ابغي ابغا ابا اريد ودي عايز خذني خدني اخذني وصلني وصلنا ودنا ودينا دلني دلوني اروح نروح اذهب روح رحني نبي نبغى نبغي ابحث دور دورلي شوف شوفلي هل وش ايش فيه فيها
    ممكن بالله الحين هنا عندي جنبي حولي اعبي عبي where take me to go find show navigate please""".split()
FILLER_PHRASES = ["لو سمحت", "take me to", "go to", "navigate to", "where is"]

# Role 7, letters said one by one, as a recogniser writes them: "كي اف سي" is kfc, "اتش اند ام" is hm. canon holds the Latin letter, "" for the word that only joins them (اند).
LETTERS = {"اي": "a", "بي": "b", "سي": "c", "دي": "d", "اف": "f", "جي": "g", "اتش": "h", "كي": "k", "ام": "m", "ان": "n", "او": "o", "ار": "r", "اس": "s", "تي": "t", "يو": "u", "دبليو": "w", "اكس": "x", "واي": "y", "زد": "z", "اند": ""}

# Role 8, another name for the whole thing. canon holds what to search for as well, | between several ways of writing it. Only well-known ones: a wrong one would send the driver elsewhere.
ALIASES = {
    "جسر البحرين": "جسر الملك فهد|king fahd causeway", "كوبري البحرين": "جسر الملك فهد|king fahd causeway", "bahrain causeway": "king fahd causeway|جسر الملك فهد", "causeway": "king fahd causeway|جسر الملك فهد",
    "الحرم": "المسجد الحرام", "الحرم المكي": "المسجد الحرام", "الحرم الشريف": "المسجد الحرام", "الحرم النبوي": "المسجد النبوي", "haram": "masjid al haram",
    "مطار الدمام": "مطار الملك فهد الدولي|king fahd international airport", "dammam airport": "king fahd international airport|مطار الملك فهد الدولي", "مطار الرياض": "مطار الملك خالد الدولي|king khalid international airport", "riyadh airport": "king khalid international airport|مطار الملك خالد الدولي",
    "مطار جدة": "مطار الملك عبدالعزيز الدولي", "jeddah airport": "king abdulaziz international airport", "مطار المدينة": "مطار الامير محمد بن عبدالعزيز", "madinah airport": "prince mohammad bin abdulaziz airport",
    "جامعة البترول": "جامعة الملك فهد للبترول|king fahd university of petroleum", "البترول والمعادن": "جامعة الملك فهد للبترول|king fahd university of petroleum", "kfupm": "king fahd university of petroleum|جامعة الملك فهد للبترول", "كاوست": "جامعة الملك عبدالله", "kaust": "king abdullah university",
    "المدينة الصناعية الجبيل": "الجبيل الصناعية", "صناعية الجبيل": "الجبيل الصناعية", "الهيئة الملكية الجبيل": "الجبيل الصناعية",
    # A thing's own full name, which the plain reading takes apart. جسر is a road word and الملك a title, so "جسر الملك فهد" was every road called فهد; المكرمة was a word no row of a city holds. Tested from Jubail, Khobar, Dammam, Riyadh.
    "جسر الملك فهد": "جسر الملك فهد|king fahd causeway", "كوبري الملك فهد": "جسر الملك فهد|king fahd causeway", "king fahd causeway": "king fahd causeway|جسر الملك فهد",
    "مكة المكرمة": "مكة", "makkah mukarramah": "مكة", "mecca mukarramah": "مكة", "المدينة المنورة": "المدينة المنورة", "madinah munawwarah": "المدينة المنورة",
    "المسجد الحرام": "المسجد الحرام", "masjid haram": "المسجد الحرام|masjid al haram", "المسجد النبوي": "المسجد النبوي", "masjid nabawi": "المسجد النبوي",
    "مطار الملك خالد": "مطار الملك خالد الدولي|king khalid international airport", "king khalid airport": "king khalid international airport|مطار الملك خالد الدولي",
    "مطار الملك فهد": "مطار الملك فهد الدولي|king fahd international airport", "king fahd airport": "king fahd international airport|مطار الملك فهد الدولي",
    "مطار الملك عبدالعزيز": "مطار الملك عبدالعزيز الدولي|king abdulaziz international airport", "king abdulaziz airport": "king abdulaziz international airport|مطار الملك عبدالعزيز الدولي",
    "المركز المالي": "مركز الملك عبدالله المالي|king abdullah financial district", "كافد": "مركز الملك عبدالله المالي|king abdullah financial district", "kafd": "king abdullah financial district|مركز الملك عبدالله المالي",
    "ساتورب": "satorp", "العلا": "محافظة العلا", "alula": "محافظة العلا", "ula": "محافظة العلا",
}

# One spelling of a word that stands for one thing, so that the rows with any of them answer: rows write شاورما, people type شاورمه.
CANON = {
    "شاورما": "شاورما شاورمه شاورمة شورما shawarma shawerma shawarmah", "مشويات": "مشويات مشاوي grill", "بروست": "بروست broast", "كرك": "كرك karak", "عود": "عود oud",
    "كورنيش": "كورنيش corniche", "ممشي": "ممشى ممشي walkway", "بوفيه": "بوفيه بوفية بوفيات", "كافتيريا": "كافتيريا كفتيريا كافيتريا cafeteria", "تمور": "تمور تمر dates",
    "مطبخ": "مطبخ مطابخ", "دواجن": "دواجن poultry", "امارة": "إمارة امارة", "مكيفات": "مكيفات تكييف", "مطبعة": "مطبعة طباعة printing", "محامي": "محامي محاماة lawyer",
    "سطحة": "سطحة سطحات", "عباية": "عبايات عباية", "كمبيوتر": "كمبيوتر حاسب computer", "سكراب": "سكراب تشليح", "شيشة": "شيشة معسل hookah shisha",
}

# By hand: a place the sources do not hold as a place. (Arabic, Latin, other names, kind, lat, lon)
HAND_PLACES = [
    ("الجبيل الصناعية", "Jubail Industrial City", ["مدينة الجبيل الصناعية", "الجبيل الصناعيه", "Jubail Industrial", "Madinat Al Jubail Al Sinaiyah", "الهيئة الملكية بالجبيل"], "town", 27.10, 49.57),
]

# Names of known chains and the kind each must be: a build that files one of these under another kind stops (a batch of Overture rows filed كنتاكي under pharmacy).
CHAIN_KINDS = {
    "كنتاكي": "fast_food", "kfc": "fast_food", "هارديز": "fast_food", "hardees": "fast_food", "صب واي": "fast_food", "subway": "fast_food", "ماكدونالدز": "fast_food", "mcdonalds": "fast_food",
    "بيتزا هت": "pizza", "pizza hut": "pizza", "كرسبي كريم": "sweets", "ستاربكس": "coffee", "starbucks": "coffee", "البيك": "fast_food", "albaik": "fast_food", "كودو": "fast_food", "kudu": "fast_food", "هرفي": "fast_food",
    "النهدي": "pharmacy", "صيدلية النهدي": "pharmacy", "nahdi": "pharmacy", "الدريس": "fuel", "aldrees": "fuel", "ساسكو": "fuel", "sasco": "fuel", "العثيم": "supermarket", "بنده": "supermarket", "panda": "supermarket",
    "مصرف الراجحي": "bank", "البنك الاهلي": "bank", "جرير": "books", "ارامكس": "shipping", "aramex": "shipping", "سمسا": "shipping", "smsa": "shipping",
}

# Words for a shop's name that say a pharmacy is one: a row Overture calls a pharmacy with none of these is checked against what the same name is elsewhere.
PHARMACY_WORDS = re.compile(r"صيدل|دواء|ادوي|أدوي|pharm|drug|nahdi|نهدي|whites|وايتس|dawaa|dawa\b|care\b|كير\b", re.I)

# Latin word <-> Arabic word, used at index time only: a shop named in Latin letters answers to its Arabic words, and the
# other way round. A word can have several translations.
DICT = {
    "pharmacy": "صيدليه", "pharmacies": "صيدليات", "hospital": "مستشفي", "restaurant": "مطعم", "restaurants": "مطاعم", "cafe": "كافيه مقهي", "coffee": "قهوه كوفي", "bakery": "مخبز",
    "bakeries": "مخابز", "burger": "برجر برقر", "burgers": "برجر", "pizza": "بيتزا", "shawarma": "شاورما", "sweets": "حلويات", "juice": "عصير", "chicken": "دجاج", "broast": "بروست",
    "fish": "سمك", "seafood": "اسماك", "grill": "مشويات", "kitchen": "مطبخ", "food": "اكل", "foods": "اغذيه", "mall": "مول", "market": "ماركت سوق", "markets": "اسواق",
    "supermarket": "سوبرماركت", "hypermarket": "هايبر", "hyper": "هايبر", "mini": "ميني", "center": "مركز سنتر", "centre": "مركز سنتر", "school": "مدرسه", "schools": "مدارس",
    "university": "جامعه", "college": "كليه", "institute": "معهد", "academy": "اكاديميه", "hotel": "فندق", "hotels": "فنادق", "resort": "منتجع", "suites": "اجنحه", "apartments": "شقق",
    "tower": "برج", "towers": "ابراج", "plaza": "بلازا", "mosque": "مسجد", "masjid": "مسجد", "bank": "بنك", "company": "شركه", "establishment": "مؤسسه", "group": "مجموعه",
    "trading": "تجاره", "contracting": "مقاولات", "park": "حديقه بارك", "garden": "حديقه", "gardens": "حدايق", "beach": "شاطي", "corniche": "كورنيش", "airport": "مطار", "station": "محطه",
    "road": "طريق", "street": "شارع", "king": "ملك", "prince": "امير", "princess": "اميره", "queen": "ملكه", "sheikh": "شيخ", "shaikh": "شيخ", "city": "مدينه", "gate": "بوابه",
    "clinic": "عياده", "clinics": "عيادات", "medical": "طبي", "dental": "اسنان", "dentist": "اسنان", "health": "صحي", "lab": "مختبر", "laboratory": "مختبر", "cars": "سيارات", "car": "سيارات",
    "auto": "سيارات", "motors": "سيارات", "wash": "مغسله غسيل", "laundry": "مغسله", "petrol": "بنزين", "fuel": "وقود", "gas": "غاز بنزين", "oil": "زيت", "tires": "كفرات", "tyres": "كفرات",
    "salon": "صالون", "barber": "حلاق", "gym": "جيم", "fitness": "لياقه", "club": "نادي", "stadium": "ملعب استاد", "sports": "رياضه", "palace": "قصر", "villa": "فيلا", "villas": "فلل",
    "furniture": "اثاث", "gold": "ذهب", "jewelry": "مجوهرات", "jewellery": "مجوهرات", "perfume": "عطور", "perfumes": "عطور", "oud": "عود", "mobile": "جوال موبايل", "electronics": "الكترونيات",
    "library": "مكتبه", "bookstore": "مكتبه", "travel": "سفر", "tourism": "سياحه", "insurance": "تامين", "exchange": "صرافه", "national": "وطني", "international": "دولي عالميه", "global": "عالميه",
    "arabian": "عربي", "arab": "عربي", "saudi": "سعودي سعوديه", "gulf": "خليج", "eastern": "شرقيه", "east": "شرق", "west": "غرب", "north": "شمال", "south": "جنوب", "new": "جديد", "old": "قديم",
    "grand": "جراند", "royal": "رويال ملكي", "star": "ستار", "sun": "صن", "blue": "ازرق", "red": "احمر", "green": "اخضر", "white": "ابيض", "house": "بيت", "home": "بيت", "family": "عائله",
    "world": "عالم", "land": "ارض", "sea": "بحر", "bay": "خليج", "island": "جزيره", "mountain": "جبل", "valley": "وادي", "wadi": "وادي", "jabal": "جبل", "jebel": "جبل", "ain": "عين", "bir": "بير",
    "umm": "ام", "um": "ام", "abu": "ابو", "abd": "عبد", "ladies": "نسائي", "women": "نساء", "men": "رجال", "mens": "رجالي", "kids": "اطفال", "children": "اطفال", "maternity": "ولاده",
    "general": "عام", "military": "عسكري", "industrial": "صناعيه", "factory": "مصنع", "office": "مكتب", "services": "خدمات", "service": "خدمه", "exit": "مخرج", "bus": "باص", "train": "قطار",
    "metro": "مترو", "port": "ميناء", "cinema": "سينما", "museum": "متحف", "castle": "قلعه", "fort": "قلعه", "farm": "مزرعه", "tea": "شاي", "karak": "كرك", "ice": "ايس", "cream": "كريم",
    "pastry": "معجنات", "pastries": "معجنات", "dates": "تمور", "honey": "عسل", "water": "ماء", "wedding": "افراح", "hall": "قاعه", "first": "اول", "second": "ثاني", "third": "ثالث", "ring": "دائري",
    "roundabout": "دوار", "bridge": "جسر", "tunnel": "نفق", "district": "حي", "gulf": "خليج", "riyadh": "رياض", "riyad": "رياض", "jeddah": "جده", "jedda": "جده", "jiddah": "جده", "makkah": "مكه", "mecca": "مكه",
    "madinah": "مدينه", "medina": "مدينه", "madina": "مدينه", "dammam": "دمام", "khobar": "خبر", "khubar": "خبر", "dhahran": "ظهران", "jubail": "جبيل", "jubayl": "جبيل", "qatif": "قطيف", "qateef": "قطيف",
    "hofuf": "هفوف", "hafuf": "هفوف", "ahsa": "احساء", "hasa": "احساء", "taif": "طايف", "tabuk": "تبوك", "tabouk": "تبوك", "abha": "ابها", "jazan": "جازان", "jizan": "جيزان", "najran": "نجران", "yanbu": "ينبع",
    "buraidah": "بريده", "buraydah": "بريده", "hail": "حايل", "unaizah": "عنيزه", "kharj": "خرج", "sakaka": "سكاكا", "arar": "عرعر", "khafji": "خفجي", "safwa": "صفوي", "saihat": "سيهات", "tarut": "تاروت",
    "ras": "راس", "tanura": "تنوره", "tanurah": "تنوره", "aramco": "ارامكو", "sabic": "سابك", "mohammed": "محمد", "mohamed": "محمد", "muhammad": "محمد", "mohammad": "محمد", "ahmed": "احمد", "ahmad": "احمد",
    "abdullah": "عبدالله", "abdulaziz": "عبدالعزيز", "aziz": "عزيز", "fahd": "فهد", "fahad": "فهد", "faisal": "فيصل", "faysal": "فيصل", "khalid": "خالد", "khaled": "خالد", "saud": "سعود", "salman": "سلمان",
    "abdulrahman": "عبدالرحمن", "rahman": "رحمن", "ali": "علي", "omar": "عمر", "umar": "عمر", "othman": "عثمان", "uthman": "عثمان", "hassan": "حسن", "hasan": "حسن", "hussein": "حسين", "hussain": "حسين", "ibrahim": "ابراهيم",
}

# Brands that must work both ways whatever the data holds. The build adds what OSM's brand:ar and name:ar hold, and pairs found in Overture's dual-script names.
BRANDS = {
    "albaik": "البيك", "al baik": "البيك", "al-baik": "البيك", "carrefour": "كارفور", "starbucks": "ستاربكس", "kudu": "كودو", "aldrees": "الدريس", "al drees": "الدريس", "sasco": "ساسكو",
    "panda": "بنده", "hyperpanda": "هايبر بنده", "hyper panda": "هايبر بنده", "lulu": "لولو", "jarir": "جرير", "herfy": "هرفي", "mcdonalds": "ماكدونالدز", "mcdonald": "ماكدونالدز", "kfc": "كنتاكي",
    "burger king": "برجر كنج", "hardees": "هارديز", "dominos": "دومينوز", "pizza hut": "بيتزا هت", "papa johns": "بابا جونز", "subway": "صب واي", "dunkin": "دانكن", "dunkin donuts": "دانكن دونتس",
    "baskin robbins": "باسكن روبنز", "tim hortons": "تيم هورتنز", "costa": "كوستا", "barns": "بارنز", "dose": "دوز", "half million": "نصف مليون", "othaim": "العثيم", "al othaim": "العثيم",
    "danube": "الدانوب", "bindawood": "بن داود", "tamimi": "التميمي", "extra": "اكسترا", "saco": "ساكو", "ikea": "ايكيا", "home centre": "هوم سنتر", "centrepoint": "سنتربوينت", "nahdi": "النهدي",
    "al nahdi": "النهدي", "aldawaa": "الدواء", "al dawaa": "الدواء", "al dawa": "الدواء", "petromin": "بترومين", "aramco": "ارامكو", "alrajhi": "الراجحي", "al rajhi": "الراجحي", "alahli": "الاهلي",
    "al ahli": "الاهلي", "snb": "الاهلي", "riyad bank": "بنك الرياض", "samba": "سامبا", "sab": "ساب", "alinma": "الانماء", "al inma": "الانماء", "albilad": "البلاد", "al bilad": "البلاد",
    "aljazira": "الجزيره", "al jazira": "الجزيره", "stc": "اس تي سي", "mobily": "موبايلي", "zain": "زين", "naft": "نفط", "gulf": "الخليج", "mazaya": "مزايا", "maidan": "ميدان", "smsa": "سمسا", "aramex": "ارامكس",
    "saptco": "سابتكو", "shawarmer": "شاورمر", "maestro pizza": "مايسترو بيتزا", "maestro": "مايسترو", "mama noura": "ماما نورة", "al tazaj": "الطازج", "altazaj": "الطازج", "tazaj": "الطازج", "shawaya": "شوايه",
    "little caesars": "ليتل سيزر", "applebees": "ابلبيز", "chilis": "تشيليز", "krispy kreme": "كرسبي كريم", "caribou": "كاريبو", "nespresso": "نسبريسو", "tamkeen": "تمكين", "toyota": "تويوتا", "hyundai": "هيونداي",
}
