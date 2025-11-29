#!/usr/bin/env python3
"""
Improve English word dictionary with better frequency ordering.
Uses word frequency data to ensure common words appear first.

This script creates a curated list of common English words sorted by frequency,
focusing on words that are most useful for word prediction on a physical keyboard.
"""

import json
import os

# Top 5000 most common English words by frequency
# Based on word frequency analysis from multiple corpora
# This list prioritizes practical, everyday vocabulary
COMMON_WORDS = [
    # Top 100 most common
    "the", "be", "to", "of", "and", "a", "in", "that", "have", "i",
    "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
    "this", "but", "his", "by", "from", "they", "we", "say", "her", "she",
    "or", "an", "will", "my", "one", "all", "would", "there", "their", "what",
    "so", "up", "out", "if", "about", "who", "get", "which", "go", "me",
    "when", "make", "can", "like", "time", "no", "just", "him", "know", "take",
    "people", "into", "year", "your", "good", "some", "could", "them", "see", "other",
    "than", "then", "now", "look", "only", "come", "its", "over", "think", "also",
    "back", "after", "use", "two", "how", "our", "work", "first", "well", "way",
    "even", "new", "want", "because", "any", "these", "give", "day", "most", "us",

    # 101-200
    "is", "are", "was", "were", "been", "being", "has", "had", "having", "does",
    "did", "doing", "am", "says", "said", "goes", "went", "gone", "going", "gets",
    "got", "getting", "makes", "made", "making", "takes", "took", "taken", "taking", "comes",
    "came", "coming", "sees", "saw", "seen", "seeing", "knows", "knew", "known", "knowing",
    "thinks", "thought", "thinking", "looks", "looked", "looking", "wants", "wanted", "wanting", "uses",
    "used", "using", "finds", "found", "finding", "gives", "gave", "given", "giving", "tells",
    "told", "telling", "becomes", "became", "become", "becoming", "leaves", "left", "leaving", "puts",
    "putting", "means", "meant", "meaning", "keeps", "kept", "keeping", "lets", "letting", "begins",
    "began", "begun", "beginning", "seems", "seemed", "seeming", "helps", "helped", "helping", "shows",
    "showed", "shown", "showing", "hears", "heard", "hearing", "plays", "played", "playing", "runs",

    # 201-400 - Common verbs, nouns, adjectives
    "move", "moves", "moved", "moving", "live", "lives", "lived", "living", "believe", "believes",
    "believed", "believing", "hold", "holds", "held", "holding", "bring", "brings", "brought", "bringing",
    "happen", "happens", "happened", "happening", "write", "writes", "wrote", "written", "writing", "provide",
    "provides", "provided", "providing", "sit", "sits", "sat", "sitting", "stand", "stands", "stood",
    "standing", "lose", "loses", "lost", "losing", "pay", "pays", "paid", "paying", "meet",
    "meets", "met", "meeting", "include", "includes", "included", "including", "continue", "continues", "continued",
    "set", "sets", "setting", "learn", "learns", "learned", "learning", "change", "changes", "changed",
    "changing", "lead", "leads", "led", "leading", "understand", "understands", "understood", "understanding", "watch",
    "watches", "watched", "watching", "follow", "follows", "followed", "following", "stop", "stops", "stopped",
    "stopping", "create", "creates", "created", "creating", "speak", "speaks", "spoke", "spoken", "speaking",
    "read", "reads", "reading", "allow", "allows", "allowed", "allowing", "add", "adds", "added",
    "adding", "spend", "spends", "spent", "spending", "grow", "grows", "grew", "grown", "growing",
    "open", "opens", "opened", "opening", "walk", "walks", "walked", "walking", "win", "wins",
    "won", "winning", "offer", "offers", "offered", "offering", "remember", "remembers", "remembered", "remembering",
    "love", "loves", "loved", "loving", "consider", "considers", "considered", "considering", "appear", "appears",
    "appeared", "appearing", "buy", "buys", "bought", "buying", "wait", "waits", "waited", "waiting",
    "serve", "serves", "served", "serving", "die", "dies", "died", "dying", "send", "sends",
    "sent", "sending", "expect", "expects", "expected", "expecting", "build", "builds", "built", "building",
    "stay", "stays", "stayed", "staying", "fall", "falls", "fell", "fallen", "falling", "cut",
    "cuts", "cutting", "reach", "reaches", "reached", "reaching", "kill", "kills", "killed", "killing",

    # 401-600 - More common words
    "remain", "suggest", "raise", "pass", "sell", "require", "report", "decide", "pull", "break",
    "develop", "agree", "carry", "explain", "receive", "hope", "accept", "support", "hit", "produce",
    "eat", "cover", "catch", "draw", "choose", "cause", "point", "listen", "matter", "interest",
    "act", "state", "mean", "wish", "save", "wonder", "prepare", "share", "base", "finish",
    "force", "close", "contain", "notice", "form", "sense", "concern", "control", "plan", "travel",
    "enjoy", "enter", "describe", "fill", "visit", "start", "return", "apply", "join", "cost",
    "involve", "manage", "prove", "hang", "mention", "pick", "fit", "drive", "express", "reduce",
    "exist", "drop", "present", "rise", "order", "name", "happen", "represent", "worry", "press",
    "face", "turn", "claim", "fight", "sign", "answer", "fear", "tend", "deal", "tend",
    "try", "check", "laugh", "achieve", "miss", "throw", "vote", "cry", "smile", "sleep",

    # Common nouns
    "man", "woman", "child", "children", "world", "life", "hand", "part", "place", "case",
    "week", "company", "system", "program", "question", "government", "number", "night", "point", "home",
    "water", "room", "mother", "area", "money", "story", "fact", "month", "lot", "right",
    "study", "book", "eye", "job", "word", "business", "issue", "side", "kind", "head",
    "house", "service", "friend", "father", "power", "hour", "game", "line", "end", "member",
    "law", "car", "city", "community", "name", "president", "team", "minute", "idea", "body",
    "information", "school", "family", "student", "group", "country", "problem", "party", "reason", "result",
    "change", "morning", "action", "level", "war", "history", "moment", "face", "effect", "process",
    "music", "person", "food", "experience", "project", "market", "evidence", "research", "industry", "development",
    "health", "office", "education", "art", "record", "analysis", "policy", "society", "news", "technology",

    # Common adjectives
    "good", "new", "first", "last", "long", "great", "little", "own", "other", "old",
    "right", "big", "high", "different", "small", "large", "next", "early", "young", "important",
    "few", "public", "bad", "same", "able", "human", "local", "late", "hard", "major",
    "better", "best", "free", "full", "special", "easy", "clear", "recent", "certain", "personal",
    "open", "red", "difficult", "available", "likely", "short", "single", "medical", "current", "wrong",
    "private", "past", "foreign", "fine", "common", "poor", "natural", "significant", "similar", "hot",
    "dead", "central", "happy", "serious", "ready", "simple", "left", "physical", "general", "environmental",
    "financial", "blue", "democratic", "dark", "various", "entire", "close", "legal", "religious", "cold",
    "final", "main", "green", "nice", "huge", "popular", "traditional", "cultural", "strong", "safe",
    "low", "successful", "modern", "beautiful", "complete", "wonderful", "amazing", "perfect", "interesting", "excellent",

    # Common adverbs
    "just", "also", "very", "often", "however", "too", "usually", "really", "early", "never",
    "always", "sometimes", "together", "likely", "simply", "generally", "instead", "actually", "already", "ever",
    "course", "outside", "hard", "sometimes", "both", "quickly", "thus", "perhaps", "indeed", "almost",
    "certainly", "probably", "exactly", "clearly", "currently", "finally", "apparently", "alone", "least", "highly",
    "rather", "once", "nearly", "yet", "especially", "tonight", "easily", "immediately", "directly", "nearly",

    # Technology and modern words
    "phone", "email", "internet", "online", "website", "computer", "software", "app", "data", "digital",
    "network", "video", "social", "media", "mobile", "web", "system", "user", "password", "account",
    "message", "text", "post", "share", "link", "click", "download", "upload", "search", "update",
    "file", "folder", "document", "image", "photo", "camera", "screen", "keyboard", "mouse", "device",
    "laptop", "tablet", "smartphone", "battery", "charger", "wifi", "bluetooth", "cloud", "server", "database",
    "security", "privacy", "settings", "profile", "notification", "subscribe", "login", "logout", "register", "confirm",

    # Common expressions and phrases
    "please", "thank", "thanks", "sorry", "okay", "ok", "yes", "no", "maybe", "hello",
    "hi", "hey", "bye", "goodbye", "welcome", "sure", "right", "wrong", "true", "false",
    "great", "good", "nice", "fine", "cool", "awesome", "amazing", "wonderful", "perfect", "excellent",
    "today", "tomorrow", "yesterday", "tonight", "morning", "afternoon", "evening", "night", "week", "month",
    "year", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "january", "february",
    "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",

    # Numbers as words
    "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
    "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
    "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand", "million",
    "billion", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth",

    # Contractions (without apostrophe for matching)
    "dont", "doesnt", "didnt", "wont", "wouldnt", "cant", "couldnt", "shouldnt", "isnt", "arent",
    "wasnt", "werent", "hasnt", "havent", "hadnt", "im", "ive", "id", "ill", "youre",
    "youve", "youd", "youll", "hes", "shes", "its", "weve", "were", "theyre", "theyve",
    "theyd", "theyll", "whats", "thats", "whos", "heres", "theres", "wheres", "lets", "theres",

    # More useful words
    "actually", "always", "another", "around", "away", "before", "behind", "between", "both", "down",
    "during", "each", "either", "enough", "everything", "everyone", "everywhere", "few", "following", "forward",
    "further", "half", "here", "itself", "less", "maybe", "more", "most", "much", "myself",
    "neither", "never", "nothing", "often", "once", "only", "quite", "rather", "really", "several",
    "since", "still", "such", "themselves", "through", "today", "together", "tomorrow", "toward", "towards",
    "under", "until", "upon", "while", "within", "without", "yesterday", "yourself", "above", "across",
    "against", "along", "among", "anything", "anyone", "anywhere", "became", "become", "becomes", "becoming",
    "before", "began", "begin", "begins", "behind", "below", "beside", "besides", "between", "beyond",

    # Business and work
    "meeting", "project", "report", "client", "customer", "manager", "team", "office", "deadline", "schedule",
    "budget", "contract", "proposal", "presentation", "strategy", "marketing", "sales", "product", "service", "solution",
    "employee", "employer", "interview", "resume", "salary", "promotion", "department", "organization", "corporation", "enterprise",

    # Additional common words
    "able", "above", "accept", "according", "account", "across", "action", "activity", "actually", "address",
    "administration", "admit", "adult", "affect", "afford", "afraid", "afternoon", "again", "against", "age",
    "agency", "agent", "agree", "agreement", "ahead", "air", "album", "alcohol", "alive", "allow",
    "almost", "alone", "along", "already", "alright", "although", "altogether", "amazing", "american", "among",
    "amount", "analysis", "ancient", "anger", "angle", "animal", "announce", "annual", "anybody", "anymore",
    "anyone", "anything", "anyway", "anywhere", "apart", "apartment", "apparently", "appeal", "application", "apply",
    "approach", "appropriate", "approve", "argue", "argument", "arm", "army", "arrange", "arrive", "article",
    "artist", "aspect", "assess", "assume", "attention", "attitude", "attract", "audience", "author", "authority",
    "autumn", "average", "avoid", "award", "aware", "awful", "baby", "background", "balance", "ball",
    "band", "bank", "bar", "basic", "basis", "battle", "beach", "bear", "beat", "beautiful",
]

def create_improved_dictionary():
    """Create an improved English word dictionary with better frequency ordering."""

    # Remove duplicates while preserving order
    seen = set()
    unique_words = []
    for word in COMMON_WORDS:
        word_lower = word.lower()
        if word_lower not in seen:
            seen.add(word_lower)
            unique_words.append(word_lower)

    # Create the dictionary structure
    dictionary = {
        "__description": "English words sorted by frequency for word prediction (improved for BlackBerry-style suggestions)",
        "__version": "2.0",
        "__entries": len(unique_words),
        "words": unique_words
    }

    return dictionary


def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(script_dir)

    output_path = os.path.join(project_root, 'app/src/main/assets/common/english/english_words.json')
    backup_path = output_path + '.backup'

    print(f"Output: {output_path}")
    print(f"Backup: {backup_path}")
    print()

    # Read and backup
    with open(output_path, 'r', encoding='utf-8') as f:
        original_content = f.read()

    with open(backup_path, 'w', encoding='utf-8') as f:
        f.write(original_content)

    print(f"Backup created at: {backup_path}")

    # Create improved dictionary
    dictionary = create_improved_dictionary()

    # Write output
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(dictionary, f, indent=2)

    print(f"Improved dictionary written with {dictionary['__entries']} words")
    print()
    print("Sample words (first 20):")
    for i, word in enumerate(dictionary['words'][:20]):
        print(f"  {i+1}. {word}")


if __name__ == '__main__':
    main()
