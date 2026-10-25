package mod.rs6048.aiplayer.brain.neural;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>Semantic acquisition</b> (语义习得) — the brain learns what words mean from its own experience,
 * instead of being handed a dictionary.
 *
 * <p>The principle is exactly the world-stream's: watch what co-occurs and let the association grow.
 * Every time the bot hears a line (from its partner) or says one itself, it records which percept
 * features were active at that moment. Over time a word accumulates a signature — "fire" keeps
 * appearing while {@link Percept#F_ON_FIRE} is set, "tired" while the fatigue/panic features are up.
 * The mapping is queried two ways:
 * <ul>
 *   <li>{@link #topWordsFor} — "given this state, what do I tend to say?"</li>
 *   <li>{@link #featuresFor} — "given this word, what state does it point at?"</li>
 * </ul>
 * Nothing is hard-coded: the associations are statistics of the bot's own experience.
 */
public final class SemanticLearner {
	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("aiplayer-semantic");

	/** A word this strongly bound to a state is considered a learned catchphrase and reinforced less. */
	private static final float SATURATION = 0.25f;

	// word/phase tables live in the shared AssociativeMemory (the single learning store).

	private AssociativeMemory assoc;
	/** Statistical word-order combiner: learns how words follow each other and recombines them into
	 * novel sequences (the "grammar burst"). Fed on every phrase the brain hears or says. */
	private final NGramComposer composer = new NGramComposer();

	/** The word-order composer behind phrase splitting / recombination. */
	public NGramComposer composer() {
		return composer;
	}

	/** Bind this learner to the shared associative memory (the single learning store). */
	public void bind(AssociativeMemory a) {
		this.assoc = a;
	}
	/** long-term memory ids already consolidated into the semantics (dedup, not persisted). */
	private final java.util.Set<Long> learnedMemIds = new java.util.HashSet<>();

	// --- near-synonym clustering (embeddinggemma) ---
	/** word -> its synonym group's representative word (fire, burn, flame -> "fire"). */
	private final Map<String, String> synonymRep = new HashMap<>();
	// Case-insensitive lookup: the vocabulary keeps words in their original casing (names like "Alex"
	// stay "Alex"), while every query resolves to the canonical spelling through this map.
	private final Map<String, String> caseMap = new HashMap<>();

	private String resolveCase(String w) {
		String key = w.toLowerCase(Locale.ROOT);
		String canonical = caseMap.get(key);
		return canonical != null ? canonical : w;
	}

	private void registerCase(String w) {
		caseMap.put(w.toLowerCase(Locale.ROOT), w);
	}
	private volatile boolean clustering = false;
	private long lastCluster = -10_000;
	private static final long CLUSTER_COOLDOWN = 600; // ticks between re-clusters
	private static final float SYNONYM_COSINE = 0.72f; // group threshold (embedding cosine)

	/**
	 * Turn a line into word tokens. English words are split on whitespace; a CJK run (Chinese has no
	 * spaces) is kept as one token (CJK codepoints are preserved, not stripped) so a whole Chinese
	 * phrase like "这是个房子" can bind to the state it was said in.
	 * <p>Registry names ("minecraft:player", "twilightforest:wooden_axe", "minecraft:entity.player.hurt")
	 * are kept as <b>one</b> token: the namespace colon and the dot/underscore separators inside the
	 * path stay part of the word, otherwise the namespace boundary is lost and "minecraft:player" would
	 * be learned as the two unrelated words "minecraft player". Ordinary text keeps the normal split
	 * (periods and underscores still delimit everyday words, not namespaces).
	 */
	public static List<String> tokenize(String text) {
		if (text == null || text.isBlank()) {
			return List.of();
		}
		// Protect registry names first: any "letters/digits/underscore : letters/digits/./_//" is
		// swapped to a private placeholder, tokenized, then restored — so the whole registered name
		// comes back as a single token regardless of the colon/dot/underscore/slash stripping below.
		// The slash is included: advancement ids like "minecraft:recipes/decoration/crafting_table"
		// are one registered name, not three words ("minecraft:recipes", "decoration", "crafting_table").
		java.util.List<String> regs = new java.util.ArrayList<>();
		java.util.regex.Matcher m = java.util.regex.Pattern
				.compile("[A-Za-z0-9_]+:[A-Za-z0-9_./]+").matcher(text);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			regs.add(m.group());
			m.appendReplacement(sb, " \uE000" + (regs.size() - 1) + "\uE000 ");
		}
		m.appendTail(sb);
		// Keep original casing: names like "Alex" must stay "Alex". Lookups are case-insensitive via
		// caseMap, so "alex"/"ALEX" still resolve to the same learned word.
		// English sentence punctuation (`.`, `,`, `!`, `?`) is kept as part of a token, so a self-coined
		// "kzo!" or a written "dirt." stays ONE token whose punctuation binds to the state like a letter —
		// not stripped as noise. Registry names were already protected to placeholders above, so their
		// dots/slashes are unaffected. Every other symbol still delimits words.
		String s = sb.toString().replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff \uE000.,!?]", " ").trim();
		List<String> out = new ArrayList<>();
		for (String w : s.split("\\s+")) {
			if (w.isEmpty()) {
				continue;
			}
			// restore any protected registry name
			if (w.length() >= 3 && w.charAt(0) == '\uE000' && w.charAt(w.length() - 1) == '\uE000') {
				int idx = Integer.parseInt(w.substring(1, w.length() - 1));
				if (idx >= 0 && idx < regs.size()) {
					out.add(regs.get(idx));
					continue;
				}
			}
			out.add(w);
		}
		return out;
	}

	/** English stop words that carry no state meaning (ignored when learning). */
	private static final java.util.Set<String> STOPWORDS = java.util.Set.of(
			"a", "an", "the", "and", "or", "but", "of", "to", "in", "on", "at", "for", "with", "is",
			"are", "was", "were", "be", "been", "it", "its", "i", "you", "your", "my", "me", "we", "us",
			"that", "this", "these", "those", "as", "by", "from", "have", "has", "had", "do", "does",
			"did", "not", "no", "so", "if", "then", "than", "too", "very", "can", "could", "will",
			"would", "should", "just", "what", "how", "why", "when", "where", "who", "which", "about",
			"up", "out", "over", "again", "more", "most", "other", "some", "such", "only", "own",
			"same", "he", "she", "they", "them", "their", "there", "here", "because", "also", "into",
			"onto", "off", "remember", "sounds", "feel", "take", "stay", "keep", "come", "got", "get",
			"go", "going", "really", "something", "anything", "everything", "make", "need", "want",
			"see", "look", "let", "like", "one", "way", "try", "help", "sure", "thing", "right", "say",
			"said", "know", "think", "don", "t", "ll", "ve", "s", "re");

	/** Whether a word is a stop word (no meaning for state). */
	public static boolean isStopWord(String w) {
		return STOPWORDS.contains(w.toLowerCase(Locale.ROOT));
	}

	/**
	 * Model-internal tokens (e.g. "joy8", "self38") that leaked into the vocabulary from an earlier
	 * LLM pipeline. They carry no world meaning and must never be learned, recalled, or spoken.
	 */
	public static boolean isNoiseToken(String w) {
		return w != null && w.matches("(self|joy|agency|panic|curiosity|fatigue|think|reason|note)\\d+");
	}

	/**
	 * True if the token contains CJK ideographs (Chinese). The bot is asked not to learn or speak
	 * Chinese, so such tokens are never added to the learned vocabulary.
	 */
	public static boolean isCJK(String w) {
		if (w == null) {
			return false;
		}
		for (int i = 0; i < w.length(); ) {
			int cp = w.codePointAt(i);
			if (Character.isIdeographic(cp)) {
				return true;
			}
			i += Character.charCount(cp);
		}
		return false;
	}

	/**
	 * Whether a feature is an <i>event</i> feature — a concrete state worth learning words for (on
	 * fire, hurt, near lava, panicking…). Meta/always-on features (grounded, agency, language intent,
	 * expectation) are excluded so words do not bind to things that are always true.
	 */
	public static boolean isEventFeature(int f) {
		return !(f == 100            // F_GROUNDED (always true)
				|| f == 1300           // F_EXPECT
				|| f == 3500           // F_SELF_BODY
				|| (f >= 3800 && f <= 3805)  // agency / narrative meta
				|| (f >= 4000 && f <= 4010)  // language intent meta
				);
	}

	/** Record that {@code text} was heard/said while {@code features} were active. */
	public void learn(String text, Collection<Integer> features, float weight) {
		if (text == null || text.isBlank()) {
			return;
		}
		// Feed the word-order composer on every phrase (regardless of feature binding), so it can
		// learn how words follow each other and later split/recombine them into new sentences.
		// #feed-cut: namespaced registry tokens ("minecraft:player") never reach the composer either.
		this.composer.learn(stripNamespaced(text));
		if (assoc == null) {
			return;
		}
		java.util.List<Integer> ev = new java.util.ArrayList<>();
		for (int f : features) {
			if (f > 0 && isEventFeature(f)) {
				ev.add(f);
			}
		}
		if (ev.isEmpty()) {
			return; // a word only gains meaning by co-occurring with an event; idle lines bind nothing
		}
		for (String w : tokenize(text)) {
			if (isCJK(w) || isStopWord(w) || isNoiseToken(w) || isNamespaced(w)) {
				continue;
			}
			registerCase(w);
			// Saturation guard: a word that already strongly fits this state is reinforced much
			// less, so one learned word cannot snowball into an unstoppable catchphrase (only ever
			// repeating "player") while every other word stays under the spoken threshold.
			float wgt = weight;
			if (assoc.wordStrength(resolveCase(w), ev) >= SATURATION) {
				wgt *= 0.35f;
			}
			assoc.observeWord(w, ev, wgt);
		}
		// A short English utterance used in a state is also learned as a whole phrase, so the voice
		// can graduate from single keywords to whole learned lines. Whole phrases containing any
		// Chinese token are skipped too.
		List<String> toks = tokenize(text);
		if (toks.size() >= 2 && toks.size() <= 6 && toks.stream().noneMatch(SemanticLearner::isCJK)
				&& toks.stream().noneMatch(SemanticLearner::isNamespaced)) {
			assoc.observePhrase(String.join(" ", toks), ev, weight);
		}
	}

	/**
	 * #feed-exception: a NAME — a player's name, a name-tag's, an anvil rename the bot wrote itself —
	 * is one of the three allowed word-feeds. It is learned as ONE identity word bound to the current
	 * state, never tokenized apart, never a registry kind. Everything else (registry names, system
	 * lines) is blocked in {@link #learn} by {@link #isNamespaced}.
	 */
	public void learnName(String name, Collection<Integer> features, float weight) {
		if (name == null || name.isBlank()) {
			return;
		}
		this.composer.learn(name);
		if (assoc == null) {
			return;
		}
		java.util.List<Integer> ev = new java.util.ArrayList<>();
		for (int f : features) {
			if (f > 0 && isEventFeature(f)) {
				ev.add(f);
			}
		}
		if (ev.isEmpty()) {
			return;
		}
		assoc.observeWord(name, ev, weight);
	}

	/** True for a namespaced registry token ("minecraft:player", "twilightforest:hydra") — the
	 *  world's registry names are anchors, never feed; they stay out of the lexicon. */
	private static boolean isNamespaced(String w) {
		return w != null && w.indexOf(':') > 0;
	}

	/** Drop namespaced tokens before word-order learning, so a system line can never teach the
	 *  composer to chain registry names ("minecraft:player Steve died"). */
	private static String stripNamespaced(String text) {
		if (text == null || text.indexOf(':') < 0) {
			return text;
		}
		java.util.List<String> kept = new java.util.ArrayList<>();
		for (String w : text.split("\\s+")) {
			if (!isNamespaced(w)) {
				kept.add(w);
			}
		}
		return String.join(" ", kept);
	}

	/** How strongly a whole phrase is bound to the active features. */
	public float phraseStrength(String phrase, Collection<Integer> features) {
		return assoc != null ? assoc.phraseStrength(phrase, features) : 0f;
	}

	/** The phrases most associated with the active features ("how do I say this state in full"). */
	public List<String> topPhrasesFor(Collection<Integer> features, int k) {
		return assoc != null ? assoc.topPhrases(features, k) : List.of();
	}

	/** Given a set of active features, the words most associated with them ("how do I say this state"). */
	public List<String> topWordsFor(Collection<Integer> features, int k) {
		// Merge each word's associations into its synonym-group representative, so fire/burn/flame
		// count as one strong signal instead of three weak ones.
		Map<String, Float> agg = new HashMap<>();
		if (assoc != null) {
			for (Map.Entry<String, Float> e : assoc.topWords(features, Integer.MAX_VALUE)) {
				String r;
				synchronized (synonymRep) {
					r = synonymRep.getOrDefault(e.getKey(), e.getKey());
				}
				agg.merge(r, e.getValue(), Float::sum);
			}
		}
		List<Map.Entry<String, Float>> scored = new ArrayList<>(agg.entrySet());
		scored.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));
		List<String> out = new ArrayList<>();
		for (int i = 0; i < scored.size() && i < k; i++) {
			out.add(scored.get(i).getKey());
		}
		return out;
	}

	/**
	 * Re-cluster the learned vocabulary into near-synonym groups (fire/burn/flame → one) using
	 * embeddinggemma. Runs on a background thread so the tick is never blocked; the groups are used
	 * by {@link #topWordsFor} to merge a synonym's associations into its representative word. A
	 * missing/offline model simply leaves exact-word matching in place.
	 */
	public void maybeCluster(int minWords, long gameTime) {
		if (clustering || (assoc != null ? assoc.wordCount() : 0) < minWords) {
			return;
		}
		if (gameTime - lastCluster < CLUSTER_COOLDOWN) {
			return;
		}
		lastCluster = gameTime;
		clustering = true;
		Thread t = new Thread(() -> {
			try {
				cluster();
			} finally {
				clustering = false;
			}
		}, "aiplayer-semantic-cluster");
		t.setDaemon(true);
		t.start();
	}

	private void cluster() {
		java.util.List<String> words = new java.util.ArrayList<>(
				assoc != null ? assoc.wordView().keySet() : java.util.List.of());
		java.util.Map<String, float[]> vecs = WordEmbedder.embed(words);
		if (vecs.isEmpty()) {
			return;
		}
		Map<String, String> fresh = new HashMap<>();
		java.util.List<String> reps = new java.util.ArrayList<>();
		for (String w : words) {
			float[] vw = vecs.get(w);
			if (vw == null) {
				fresh.put(w, w);
				continue;
			}
			String rep = w;
			float best = 0f;
			for (String r : reps) {
				float c = WordEmbedder.cosine(vw, vecs.get(r));
				if (c > best) {
					best = c;
					rep = r;
				}
			}
			if (best >= SYNONYM_COSINE) {
				fresh.put(w, rep);
			} else {
				fresh.put(w, w);
				reps.add(w);
			}
		}
		synchronized (synonymRep) {
			synonymRep.clear();
			synonymRep.putAll(fresh);
		}
	}

	/** How many synonym groups exist (words merged under a representative). */
	public int synonymGroupCount() {
		synchronized (synonymRep) {
			int nonTrivial = 0;
			for (java.util.Map.Entry<String, String> e : synonymRep.entrySet()) {
				if (!e.getKey().equals(e.getValue())) {
					nonTrivial++;
				}
			}
			return nonTrivial;
		}
	}

	/**
	 * How strongly a word is bound to the given active features (sum of its co-occurrence with them).
	 * 0 = no learned link. Used by the voice to only speak words the brain is actually confident about.
	 */
	public float wordStrength(String word, Collection<Integer> features) {
		return assoc != null ? assoc.wordStrength(resolveCase(word), features) : 0f;
	}

	/** The features a word points at ("what does this word mean to me"), strongest first. */
	public List<Map.Entry<Integer, Float>> featuresFor(String word) {
		return assoc != null ? assoc.featuresFor(resolveCase(word)) : List.of();
	}

	/** Number of distinct word tokens learned. */
	public int wordCount() {
		return assoc != null ? assoc.wordCount() : 0;
	}

	/**
	 * <b>Acquired fear.</b> Remember how frightening a state was: every time the brain learns words in
	 * a state, it also records the panic felt there. Later, being in that state again raises the panic
	 * floor — the bot has learned from experience that this situation is dangerous, without any rule
	 * being hard-coded. This is how semantics turns into action.
	 */
	public void learnPanic(Collection<Integer> features, float panic) {
		if (panic <= 0.05f || assoc == null) {
			return;
		}
		// feed only the event features (not always-on meta) into the shared panic axis
		java.util.List<Integer> ev = new java.util.ArrayList<>();
		for (int f : features) {
			if (f > 0 && isEventFeature(f)) {
				ev.add(f);
			}
		}
		if (!ev.isEmpty()) {
			assoc.observeScalar(ev, AssociativeMemory.FEEL_PANIC, Math.min(panic, 1.5f), 0.15f);
		}
	}

	/**
	 * <b>Lexical conditioned fear.</b> How frightening a word has become by being learned in a
	 * frightening state: the strongest acquired-fear of the features it is bound to. Saying or hearing
	 * a word the brain once learned while in danger raises alarm again — a learned association, not a
	 * hard-coded rule.
	 */
	public float wordFear(String word) {
		if (assoc == null) {
			return 0f;
		}
		Map<Integer, Float> m = assoc.wordView().get(resolveCase(word));
		if (m == null) {
			return 0f;
		}
		float fear = 0f;
		for (int f : m.keySet()) {
			float p = assoc.value(f, AssociativeMemory.FEEL_PANIC);
			if (p > fear) {
				fear = p;
			}
		}
		return fear;
	}

	/**
	 * <b>Metacognitive self-model.</b> The words the brain has learned to fear (wordFear at or above a
	 * floor) — what it knows about its own fears, so introspection can say "I have learned to fear
	 * fire/burn" even when it is not currently on fire.
	 */
	public java.util.List<String> learnedFears(float minFear) {
		java.util.List<String> out = new java.util.ArrayList<>();
		for (String w : assoc != null ? assoc.wordView().keySet() : java.util.List.<String>of()) {
			if (wordFear(w) >= minFear) {
				out.add(w);
			}
		}
		return out;
	}

	/** Max wordFear over the English tokens of a line (Chinese tokens carry no fear). */
	public float wordFearForText(String text) {
		if (text == null || text.isBlank()) {
			return 0f;
		}
		float fear = 0f;
		for (String w : tokenize(text)) {
			float wf = wordFear(w);
			if (wf > fear) {
				fear = wf;
			}
		}
		return fear;
	}

	/**
	 * <b>Reverse semantic activation (input-side grounding).</b> Given a line the bot heard or said,
	 * return the event features each word was learned with, as feature → strength. The caller re-lights
	 * those features so hearing "fire" fires the fire senses the same way seeing actual fire does —
	 * not just fear, but the full grounded state the word points at.
	 */
	/**
	 * Delegates to the shared associative memory: a random learned word (for incubation), or null.
	 */
	public String randomKnownWord() {
		if (assoc == null) {
			return null;
		}
		// Re-draw until we land on a word that is not a leaked model token; a few tries, then give
		// up rather than ever polluting speech with "joy8".
		for (int attempt = 0; attempt < 12; attempt++) {
			String w = assoc.randomKnownWord();
			if (w != null && !isNoiseToken(w)) {
				return w;
			}
		}
		return null;
	}

	/** Remove leaked model tokens (e.g. "joy8") from the vocabulary; returns how many were removed. */
	public int cleanNoiseTokens() {
		if (assoc == null) {
			return 0;
		}
		int removed = 0;
		java.util.Iterator<java.util.Map.Entry<String, Map<Integer, Float>>> it =
				assoc.wordView().entrySet().iterator();
		while (it.hasNext()) {
			if (isNoiseToken(it.next().getKey())) {
				it.remove();
				removed++;
			}
		}
		return removed;
	}

	/** The feature signature a word is bound to (never null; empty if not learned). */
	public java.util.Map<Integer, Float> wordSignature(String wordStr) {
		return assoc != null ? assoc.wordSignature(resolveCase(wordStr)) : java.util.Map.of();
	}

	public java.util.Map<Integer, Float> wordFeatureActivations(String text) {
		java.util.Map<Integer, Float> out = new java.util.HashMap<>();
		if (assoc == null || text == null || text.isBlank()) {
			return out;
		}
		for (String w : tokenize(text)) {
			if (isCJK(w) || isStopWord(w) || isNoiseToken(w)) {
				continue;
			}
			for (java.util.Map.Entry<Integer, Float> fe : assoc.featuresFor(resolveCase(w))) {
				if (fe.getValue() >= 0.2f) {
					out.merge(fe.getKey(), fe.getValue(), Float::max);
				}
			}
		}
		return out;
	}

	/** The strongest learned fear among the active features (0 = nothing learned frightening yet). */
	public float expectedPanic(Collection<Integer> features) {
		return assoc != null ? assoc.expectedMax(AssociativeMemory.FEEL_PANIC, features) : 0f;
	}

	/**
	 * <b>Memory-driven semantics.</b> Long-term memories are a second learning channel: when an episode
	 * is consolidated, the words in its content are bound to the episode's features too — so a word the
	 * bot once said or heard in a state keeps teaching it even later, through memory rather than the
	 * live senses. Same word->feature statistics, weaker weight, deduplicated by memory id.
	 */
	public void learnFromMemory(String content, Collection<Integer> features, float weight, long memId) {
		if (content == null || content.isBlank() || memId <= 0 || !learnedMemIds.add(memId)) {
			return;
		}
		if (assoc == null) {
			return;
		}
		java.util.List<Integer> ev = new java.util.ArrayList<>();
		for (int f : features) {
			if (f > 0 && isEventFeature(f)) {
				ev.add(f);
			}
		}
		for (String w : tokenize(content)) {
			if (isCJK(w) || isStopWord(w) || isNoiseToken(w) || isNamespaced(w)) {
				continue;
			}
			registerCase(w);
			assoc.observeWord(w, ev, weight);
		}
	}

	/** How many long-term memories have been consolidated into the semantics. */
	public int learnedMemCount() {
		return learnedMemIds.size();
	}

	/** Number of word->feature associations learned. */
	public int pairCount() {
		return assoc != null ? assoc.wordPairCount() : 0;
	}

	/** Persist word->feature associations — now handled by the shared AssociativeMemory. */
	public void writeTo(com.google.gson.JsonObject root) {
		// words/phrases live in assoc (persisted by NeuralCenter as "assocWord"/"assocPhrase")
	}

	/** Load word->feature associations — handled by the shared AssociativeMemory's migration. */
	public void readFrom(com.google.gson.JsonObject root) {
		// legacy "semantic"/"semanticPhrases" keys are migrated by AssociativeMemory.readFrom
	}

	/** Serialize the strongest associations (word -> its top features) for persistence/debugging. */
	public List<String> snapshot(int words, int featsPerWord) {
		Map<String, Map<Integer, Float>> wv = assoc != null ? assoc.wordView() : java.util.Map.of();
		List<Map.Entry<String, Map<Integer, Float>>> entries = new ArrayList<>(wv.entrySet());
		entries.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));
		List<String> out = new ArrayList<>();
		for (int i = 0; i < entries.size() && i < words; i++) {
			Map.Entry<String, Map<Integer, Float>> e = entries.get(i);
			List<Map.Entry<Integer, Float>> fs = new ArrayList<>(e.getValue().entrySet());
			fs.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));
			StringBuilder sb = new StringBuilder(e.getKey()).append(':');
			for (int j = 0; j < fs.size() && j < featsPerWord; j++) {
				sb.append(fs.get(j).getKey()).append('=').append(String.format(Locale.ROOT, "%.2f", fs.get(j).getValue())).append(' ');
			}
			out.add(sb.toString().trim());
		}
		return out;
	}
}
