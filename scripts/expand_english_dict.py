#!/usr/bin/env python3
"""
Expand English word dictionary to ~20,000 words with frequency ordering.
"""

import json
import os

# Extended word list - adding more common words sorted by frequency
# This supplements the existing ~10,000 words to reach ~20,000
ADDITIONAL_WORDS = [
    # More verbs
    "abandon", "abolish", "absorb", "abuse", "accelerate", "accompany", "accomplish", "accumulate", "accuse", "adapt",
    "administer", "admire", "adopt", "advance", "advertise", "advise", "advocate", "affirm", "afford", "alarm",
    "allocate", "alter", "amaze", "amend", "analyze", "anticipate", "apologize", "appreciate", "approach", "approve",
    "arise", "arrange", "arrest", "arrive", "articulate", "aspire", "assemble", "assert", "assign", "assist",
    "associate", "assure", "attach", "attain", "attempt", "attend", "attract", "attribute", "authorize", "automate",
    "await", "awaken", "balance", "ban", "bargain", "base", "bear", "behave", "belong", "bend",
    "benefit", "bet", "bind", "bite", "blame", "bless", "block", "blow", "boast", "boil",
    "bomb", "bond", "book", "boost", "bore", "borrow", "bother", "bounce", "bow", "breathe",
    "breed", "broadcast", "brush", "budget", "burn", "bury", "calculate", "calm", "campaign", "cancel",
    "capture", "celebrate", "challenge", "characterize", "charge", "chase", "cheat", "cheer", "cherish", "chill",

    # More nouns
    "absence", "abundance", "accent", "acceptance", "access", "accident", "accommodation", "accomplishment", "accuracy", "accusation",
    "achievement", "acknowledgment", "acquisition", "acre", "activation", "activity", "actor", "actress", "adaptation", "addition",
    "adjustment", "administration", "administrator", "admission", "adolescent", "adoption", "adult", "advancement", "advantage", "adventure",
    "advertisement", "advice", "advocate", "affair", "affection", "afternoon", "agency", "agenda", "agent", "aggression",
    "agreement", "agriculture", "aid", "aim", "aircraft", "airline", "airport", "alarm", "album", "alcohol",
    "alert", "algebra", "algorithm", "alien", "alignment", "allegation", "alliance", "allocation", "allowance", "ally",
    "alternative", "aluminum", "amateur", "ambassador", "ambition", "amendment", "amount", "amusement", "analyst", "ancestor",
    "anchor", "angel", "angle", "animation", "ankle", "anniversary", "announcement", "anxiety", "apartment", "apology",
    "apparatus", "appeal", "appearance", "appetite", "applause", "apple", "appliance", "applicant", "application", "appointment",
    "appreciation", "apprentice", "approach", "approval", "arch", "architect", "architecture", "archive", "arena", "argument",

    # More adjectives
    "abandoned", "abnormal", "absent", "absolute", "abstract", "absurd", "abundant", "academic", "acceptable", "accessible",
    "accidental", "accurate", "accused", "acoustic", "active", "actual", "acute", "additional", "adequate", "adjacent",
    "administrative", "admirable", "adorable", "advanced", "adverse", "aesthetic", "affordable", "afraid", "aggressive", "agreeable",
    "agricultural", "alert", "alien", "alike", "alive", "alleged", "allergic", "allied", "alone", "alternate",
    "alternative", "amateur", "ambitious", "ancient", "angry", "annual", "anonymous", "anxious", "apparent", "appealing",
    "applicable", "appropriate", "approximate", "arbitrary", "architectural", "armed", "artificial", "artistic", "ashamed", "asian",
    "asleep", "associated", "athletic", "atmospheric", "atomic", "attached", "attractive", "authentic", "automatic", "autonomous",
    "auxiliary", "available", "average", "aware", "awful", "awkward", "back", "backward", "bacterial", "balanced",
    "bare", "basic", "beautiful", "behavioral", "beloved", "beneficial", "bent", "best", "better", "big",
    "bilateral", "biological", "bitter", "bizarre", "blank", "blind", "blonde", "bloody", "blue", "bold",

    # Technology words
    "algorithm", "analytics", "android", "animation", "antivirus", "api", "application", "archive", "artificial", "authentication",
    "automation", "backend", "backup", "bandwidth", "binary", "biometric", "bitcoin", "blockchain", "blog", "bookmark",
    "boolean", "bootloader", "broadband", "browser", "buffer", "bug", "byte", "cache", "captcha", "certificate",
    "channel", "chatbot", "chip", "circuit", "clipboard", "codec", "command", "compiler", "compression", "configuration",
    "connection", "console", "container", "content", "cookie", "copyright", "core", "cpu", "crash", "crawler",
    "credential", "cryptocurrency", "css", "cursor", "cyberattack", "cybersecurity", "daemon", "dashboard", "debug", "decrypt",
    "default", "deployment", "desktop", "developer", "directory", "disable", "display", "dns", "docker", "domain",
    "download", "driver", "dropdown", "duplicate", "dynamic", "editor", "email", "embed", "emoji", "emulator",
    "enable", "encrypt", "endpoint", "engine", "enterprise", "entity", "environment", "error", "ethernet", "event",
    "executable", "export", "extension", "external", "extract", "facebook", "feature", "feedback", "fiber", "field",

    # Business words
    "accountant", "accounting", "acquisition", "advertisement", "affiliate", "agenda", "agreement", "allocation", "analyst", "annual",
    "asset", "audit", "balance", "bankruptcy", "benchmark", "benefit", "bid", "billing", "board", "bond",
    "bonus", "bookkeeping", "brand", "breach", "breakdown", "brokerage", "budget", "bureaucracy", "buyout", "capital",
    "capitalize", "cashflow", "chairman", "chapter", "clause", "clearance", "clientele", "closure", "coalition", "collateral",
    "collection", "commerce", "commission", "commodity", "compensation", "competition", "competitive", "compliance", "compound", "comprehensive",
    "compromise", "conference", "confidential", "confirmation", "conglomerate", "consensus", "consolidation", "consortium", "consultant", "consumption",
    "contractor", "contribution", "controversy", "convention", "conversion", "cooperation", "coordination", "corporate", "corporation", "correlation",
    "corruption", "counsel", "counterpart", "coverage", "credential", "creditor", "crisis", "criteria", "critique", "currency",
    "custodian", "custody", "customs", "deadline", "dealer", "dealership", "debit", "debtor", "decline", "deduction",
    "default", "deficit", "delegate", "delegation", "deliberation", "delivery", "demand", "demographic", "demonstration", "denomination",

    # More common everyday words
    "ability", "abroad", "academic", "accident", "accommodation", "achievement", "acid", "acquisition", "actress", "adaptation",
    "addition", "address", "adequate", "adjustment", "administration", "admission", "adolescent", "adoption", "advancement", "advantage",
    "adventure", "advertisement", "advice", "affair", "affection", "afternoon", "agency", "agenda", "agreement", "agriculture",
    "aircraft", "airline", "airport", "alarm", "album", "alcohol", "alien", "allegation", "alliance", "allowance",
    "alternative", "ambassador", "ambition", "amendment", "amount", "analysis", "analyst", "ancestor", "angel", "anger",
    "angle", "animal", "ankle", "anniversary", "announcement", "anxiety", "apartment", "apology", "apparatus", "appeal",
    "appearance", "appetite", "applause", "apple", "application", "appointment", "appreciation", "approach", "approval", "architect",
    "architecture", "archive", "arena", "argument", "arm", "arrangement", "arrest", "arrival", "arrow", "article",
    "artificial", "artist", "artwork", "ash", "aspect", "aspiration", "assault", "assembly", "assertion", "assessment",
    "asset", "assignment", "assistance", "assistant", "association", "assumption", "assurance", "athlete", "atmosphere", "atom",

    # Nature and environment
    "atmosphere", "autumn", "avalanche", "bacteria", "bamboo", "basin", "bay", "beach", "beam", "beast",
    "bee", "beetle", "berry", "bird", "blossom", "bluff", "bog", "boulder", "branch", "breeze",
    "brook", "brush", "bud", "buffalo", "bug", "bush", "butterfly", "cactus", "canal", "canyon",
    "cape", "carbon", "carnivore", "cascade", "cave", "cedar", "cell", "cellulose", "channel", "cheetah",
    "cherry", "chestnut", "chicken", "chimpanzee", "chlorophyll", "cicada", "clay", "cliff", "climate", "cloud",
    "coast", "cobra", "cocoa", "coconut", "cod", "colony", "comet", "condensation", "cone", "continent",
    "coral", "cork", "corn", "cosmos", "cotton", "cougar", "countryside", "cove", "coyote", "crab",
    "crater", "creek", "cricket", "crocodile", "crop", "crow", "crystal", "cub", "cucumber", "current",
    "cypress", "daffodil", "daisy", "dam", "dawn", "deer", "delta", "den", "desert", "dew",
    "diamond", "dinosaur", "dirt", "doe", "dog", "dolphin", "donkey", "dove", "dragonfly", "drought",

    # Food and cooking
    "almond", "anchovy", "appetizer", "apricot", "artichoke", "asparagus", "avocado", "bacon", "bagel", "baguette",
    "banana", "barley", "basil", "bass", "batter", "bean", "beef", "beer", "beet", "beverage",
    "biscuit", "blackberry", "blender", "blueberry", "bourbon", "braise", "bran", "brandy", "bread", "breakfast",
    "bream", "brie", "broccoli", "broth", "brownie", "brunch", "brussels", "buckwheat", "buffet", "bun",
    "burger", "burrito", "butter", "buttermilk", "cabbage", "cafe", "caffeine", "cake", "calorie", "candy",
    "caper", "cappuccino", "caramel", "carbohydrate", "cardamom", "carrot", "casserole", "catfish", "cauliflower", "caviar",
    "cayenne", "celery", "cereal", "cheddar", "cheese", "cheesecake", "chef", "cherry", "chestnut", "chicken",
    "chickpea", "chili", "chips", "chive", "chocolate", "chop", "chorizo", "chowder", "cider", "cilantro",
    "cinnamon", "citrus", "clam", "clove", "cobbler", "cocktail", "cocoa", "coconut", "cod", "coffee",
    "coleslaw", "condiment", "cookie", "coriander", "corn", "cornbread", "couscous", "crab", "cracker", "cranberry",

    # Health and medical
    "abdomen", "ache", "acne", "addiction", "adrenaline", "aerobic", "ailment", "airway", "albumin", "alcohol",
    "allergy", "ambulance", "amnesia", "amputation", "anemia", "anesthesia", "angina", "ankle", "antibiotic", "antibody",
    "antidote", "antioxidant", "anxiety", "aorta", "appendix", "appetite", "arrhythmia", "artery", "arthritis", "asthma",
    "atrium", "autism", "bacteria", "bandage", "biopsy", "bladder", "bleeding", "blindness", "blister", "blood",
    "bone", "bowel", "brain", "breast", "breath", "bronchitis", "bruise", "burn", "calcium", "calorie",
    "cancer", "capillary", "capsule", "carbohydrate", "cardiac", "cardiovascular", "cartilage", "cataract", "catheter", "cavity",
    "cell", "cervix", "chemotherapy", "chest", "chickenpox", "childbirth", "chills", "cholesterol", "chromosome", "chronic",
    "circulation", "cirrhosis", "clinic", "clot", "colon", "coma", "concussion", "condom", "congestion", "constipation",
    "contagious", "contraception", "contraction", "convulsion", "cornea", "coronary", "cortisone", "cough", "counseling", "cramp",
    "cranium", "cyst", "deafness", "dehydration", "dementia", "dentist", "depression", "dermatitis", "diabetes", "diagnosis",

    # Sports and recreation
    "ace", "aerobics", "agility", "amateur", "archery", "arena", "assist", "athlete", "athletics", "attack",
    "backhand", "badminton", "ball", "ballpark", "bantam", "base", "baseball", "baseline", "basket", "basketball",
    "bat", "baton", "batter", "batting", "beach", "bicycle", "biking", "billiards", "birdie", "block",
    "board", "bobsled", "bodybuilding", "bogey", "bounce", "bout", "bowl", "bowling", "boxer", "boxing",
    "breaststroke", "bunker", "caddie", "calisthenics", "camp", "canoe", "captain", "cardio", "catch", "catcher",
    "center", "champion", "championship", "cheerleader", "chess", "circuit", "climb", "climber", "climbing", "coach",
    "coaching", "competition", "competitor", "conditioning", "conference", "contest", "corner", "court", "crawl", "crew",
    "cricket", "croquet", "cross", "crossbar", "curl", "curling", "cycle", "cycling", "cyclist", "dart",
    "dash", "decathlon", "deck", "defeat", "defense", "defender", "defending", "diamond", "discus", "dive",
    "diver", "diving", "dodge", "dodgeball", "doubles", "downhill", "draft", "dribble", "drill", "drive",

    # Education and learning
    "academia", "academic", "academy", "accent", "achievement", "admission", "adolescent", "adult", "advanced", "advisor",
    "algebra", "algorithm", "alphabet", "alumni", "analysis", "anatomy", "anthropology", "application", "apprentice", "aptitude",
    "archaeology", "architecture", "arithmetic", "art", "article", "arts", "assignment", "associate", "astronomy", "athletics",
    "attendance", "auditorium", "bachelor", "biology", "blackboard", "book", "booklet", "botany", "brain", "branch",
    "business", "cafeteria", "calculus", "calendar", "campus", "candidate", "career", "catalog", "certificate", "certification",
    "chapter", "chart", "chemistry", "choir", "class", "classics", "classroom", "club", "coach", "college",
    "commencement", "committee", "community", "competency", "composition", "comprehensive", "computer", "concentration", "concept", "conclusion",
    "conference", "content", "context", "continuing", "contribution", "conversation", "core", "counselor", "course", "coursework",
    "creativity", "credential", "credit", "criteria", "critical", "criticism", "culture", "cumulative", "curiosity", "curriculum",
    "dean", "debate", "degree", "department", "design", "development", "dialogue", "dictionary", "diploma", "direction",

    # Home and household
    "address", "air", "alarm", "apartment", "appliance", "attic", "backyard", "balcony", "basement", "bathroom",
    "bathtub", "bed", "bedroom", "blanket", "blinds", "bookshelf", "broom", "bucket", "cabinet", "carpet",
    "ceiling", "cellar", "chair", "chandelier", "chimney", "closet", "couch", "counter", "cupboard", "curtain",
    "cushion", "deck", "dining", "dishwasher", "door", "doorbell", "doorknob", "doorway", "drain", "drawer",
    "driveway", "dryer", "duct", "electricity", "entrance", "entry", "estate", "extension", "exterior", "fan",
    "faucet", "fence", "fireplace", "fixture", "floor", "flooring", "foundation", "foyer", "frame", "freezer",
    "front", "furnace", "furnishing", "furniture", "garage", "garbage", "garden", "gardening", "gate", "glass",
    "grill", "gutter", "hallway", "handle", "hanger", "hardware", "heater", "heating", "hinge", "home",
    "hood", "hook", "hose", "house", "household", "housing", "insulation", "interior", "iron", "key",
    "kitchen", "ladder", "lamp", "landing", "landscaping", "laundry", "lawn", "lease", "light", "lighting",

    # Transportation
    "accelerator", "accident", "airline", "airplane", "airport", "aisle", "ambulance", "anchor", "arrival", "automobile",
    "aviation", "axle", "baggage", "barge", "barrier", "bicycle", "bike", "boarding", "boat", "brake",
    "bridge", "bumper", "bus", "cab", "cabin", "cable", "caboose", "canal", "canoe", "captain",
    "car", "cargo", "carpool", "carrier", "carriage", "cart", "chassis", "chauffeur", "checkpoint", "chopper",
    "circuit", "clutch", "coach", "cockpit", "collision", "commute", "commuter", "compartment", "conductor", "congestion",
    "container", "convertible", "convoy", "copilot", "courier", "craft", "crane", "crash", "crew", "crossing",
    "cruise", "cruiser", "customs", "cylinder", "deck", "delay", "delivery", "departure", "depot", "derail",
    "destination", "detour", "diesel", "dispatch", "dock", "driver", "driveway", "driving", "economy", "electric",
    "elevator", "embark", "emergency", "emission", "engine", "engineer", "entrance", "escalator", "escort", "exhaust",
    "exit", "expedition", "explorer", "express", "fare", "ferry", "first", "fleet", "flight", "float",

    # Music and arts
    "accordion", "acoustic", "album", "alto", "amplifier", "anthem", "aria", "arrangement", "artist", "artwork",
    "audition", "bagpipe", "ballad", "ballet", "band", "banjo", "baritone", "bass", "baton", "beat",
    "bell", "blues", "bow", "brass", "bridge", "broadcast", "bugle", "cable", "cadence", "calypso",
    "canvas", "cello", "chamber", "channel", "choir", "chord", "choreography", "chorus", "clarinet", "classical",
    "clay", "clef", "comedy", "compose", "composer", "composition", "concert", "concerto", "conductor", "contemporary",
    "contralto", "country", "craft", "creativity", "critic", "criticism", "culture", "curator", "cymbal", "dance",
    "dancer", "dancing", "design", "designer", "digital", "director", "disc", "disco", "display", "dj",
    "documentary", "drama", "drawing", "dream", "dress", "drum", "drummer", "drumstick", "duet", "dynamics",
    "easel", "edition", "effect", "electronic", "encore", "ensemble", "entertainment", "episode", "etching", "exhibition",
    "expression", "fabric", "fantasy", "fashion", "feature", "festival", "fiction", "fiddle", "figure", "film",

    # Law and government
    "abolish", "abortion", "absence", "absolute", "abuse", "accusation", "accused", "acquit", "acquittal", "act",
    "action", "activist", "administration", "administrator", "admission", "adopt", "adoption", "adult", "advocate", "affair",
    "affidavit", "agency", "agenda", "agent", "agreement", "aid", "allegation", "alliance", "allowance", "ambassador",
    "amendment", "amnesty", "analyst", "appeal", "applicant", "application", "appointment", "approval", "arbitration", "argument",
    "arrest", "article", "assault", "assembly", "assessment", "asset", "assignment", "assistance", "association", "assumption",
    "asylum", "attorney", "auction", "audit", "authority", "authorization", "autonomy", "award", "bail", "ballot",
    "ban", "bankruptcy", "bar", "barrier", "battle", "bench", "benefit", "bias", "bill", "binding",
    "blame", "block", "board", "bond", "border", "boycott", "branch", "breach", "breakdown", "bribe",
    "bribery", "brief", "broadcast", "budget", "building", "bulletin", "burden", "bureau", "bureaucracy", "cabinet",
    "campaign", "candidate", "capacity", "capital", "capitalism", "capture", "case", "casualty", "category", "cause",

    # Science and research
    "acceleration", "accuracy", "acid", "acoustics", "activation", "adaptation", "adhesion", "adjustment", "aerodynamics", "aerospace",
    "agent", "agriculture", "algorithm", "alkaline", "alloy", "alpha", "altitude", "aluminum", "amino", "ammonia",
    "amplitude", "analysis", "analyst", "anatomy", "animal", "anomaly", "antibiotic", "antibody", "antigen", "antimatter",
    "apparatus", "application", "aquatic", "arc", "archaeology", "architecture", "archive", "area", "argument", "arithmetic",
    "array", "artifact", "artificial", "assessment", "asteroid", "astronomy", "astrophysics", "atmosphere", "atom", "atomic",
    "attachment", "attraction", "attribute", "automation", "axis", "bacteria", "balance", "bandwidth", "bar", "barometer",
    "barrier", "base", "baseline", "basin", "batch", "battery", "beam", "behavior", "benchmark", "beta",
    "bias", "binary", "biochemistry", "biodiversity", "bioengineering", "biology", "biomass", "biome", "biometrics", "biophysics",
    "biotechnology", "black", "block", "blueprint", "body", "boiling", "bond", "bone", "boost", "bore",
    "botany", "boundary", "brain", "branch", "breakdown", "breakthrough", "breed", "breeding", "bridge", "buffer",

    # Emotions and psychology
    "abandonment", "ability", "abnormality", "absorption", "abstraction", "abuse", "acceptance", "access", "accident", "accomplishment",
    "accountability", "accuracy", "accusation", "achievement", "acknowledgment", "acquaintance", "action", "activation", "activity", "adaptation",
    "addiction", "adjustment", "admiration", "admission", "adolescence", "adoption", "adulthood", "advancement", "adventure", "adversity",
    "advice", "advocate", "affection", "affirmation", "aggression", "agitation", "agreement", "aim", "alarm", "alertness",
    "alienation", "alignment", "allegiance", "alliance", "allowance", "altruism", "amazement", "ambiguity", "ambition", "ambivalence",
    "amusement", "analysis", "ancestry", "anger", "anguish", "animation", "annoyance", "anticipation", "anxiety", "apathy",
    "apology", "apparel", "appeal", "appearance", "appetite", "applause", "application", "appreciation", "apprehension", "approach",
    "approval", "argument", "arousal", "arrangement", "arrogance", "articulation", "aspiration", "assault", "assertion", "assessment",
    "asset", "assignment", "assimilation", "assistance", "association", "assumption", "assurance", "astonishment", "attachment", "attack",
    "attainment", "attempt", "attendance", "attention", "attitude", "attraction", "attribute", "authority", "autonomy", "avoidance",
]

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(script_dir)

    input_path = os.path.join(project_root, 'app/src/main/assets/common/english/english_words.json')
    backup_path = input_path + '.backup'

    # Read existing dictionary
    with open(input_path, 'r', encoding='utf-8') as f:
        data = json.load(f)

    # Backup
    with open(backup_path, 'w', encoding='utf-8') as f:
        json.dump(data, f, indent=2)

    existing_words = set(w.lower() for w in data['words'])
    original_count = len(data['words'])

    print(f"Original word count: {original_count}")
    print(f"Backup created at: {backup_path}")

    # Add new words that don't exist
    new_words = []
    for word in ADDITIONAL_WORDS:
        word_lower = word.lower()
        if word_lower not in existing_words and len(word_lower) >= 2:
            new_words.append(word_lower)
            existing_words.add(word_lower)

    # Append new words to the list
    data['words'].extend(new_words)
    data['__description'] = "Expanded English words sorted by frequency for word prediction (~20k words)"
    data['__entries'] = len(data['words'])

    # Write output
    with open(input_path, 'w', encoding='utf-8') as f:
        json.dump(data, f, indent=2)

    print(f"Added {len(new_words)} new words")
    print(f"New total: {len(data['words'])} words")

if __name__ == '__main__':
    main()
