package mod.rs6048.aiplayer.brain.neural;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * <b>Phonation layer</b> — the seed of language self-invention (route 甲). A creature that only ever
 * repeats words it heard never <i>invents</i>; this layer gives the bot a mouth to make new sounds and
 * a pressure to make them when it has something to say but no learned word for it.
 *
 * <p>Mechanics:
 * <ul>
 *   <li><b>Referential pressure</b> ({@link #referentialPressure}): when something salient is happening
 *       (a new entity, a sound, a structure, hurt, fire) but no learned word is strongly bound to this
 *       state, the brain feels a pressure to <i>say something about it</i> — the same urge that makes a
 *       child babble at an object before it has a name.</li>
 *   <li><b>Coinage</b> ({@link #tick}): under that pressure it emits a brand-new token — 1–4 letters
 *       drawn from a 24-letter alphabet (the minimal closed set we supply; everything else the bot must
 *       build itself). The new word is not a canned line; it is a fresh mouth-sound.</li>
 *   <li><b>Assessment</b>: the invented word is then fed through the normal {@link SemanticLearner}
 *       channel, so it binds to the current percept features. If the same state recurs and the word keeps
 *       being useful it is reinforced (its strength rises); if it never pays off it decays like any unused
 *       word. Whether a coinage <i>sticks</i> is learned — the brain is not told it was a good invention.</li>
 * </ul>
 * This is exactly the "no hard-coded meanings, minimal seed set" line: 24 letters, the words of the
 * world (fed as short registered names), and the statistical association machinery. Syntax and stable
 * reference grow out of that, not out of a dictionary.
 */
public final class PhonationLayer {

	/** The minimal closed alphabet (24 letters). Everything else the brain must build from these. */
	private static final String LETTERS = "abcdefghijklmnoprstuvwyz"; // 24 (q, x omitted)
	/** Longest coinage, in letters. */
	private static final int MAX_LEN = 4;
	/** English punctuation — like the letters, the mouth may draw these too (self-invented prosody). */
	private static final String[] PUNCT = {".", ",", "!", "?", "..."};
	/** Natural chance that a coined token ends with a punctuation mark (a symbol, not a rule). */
	private static final float PUNCT_CHANCE = 0.20f;
	/** A state must be this salient before the brain bothers to name it. */
	private static final float PRESSURE_THRESHOLD = 0.55f;
	/** How strongly a learned word must fit before the brain "already has a word for this". */
	private static final float HAVE_WORD = 0.22f;
	/** Cooldown between coinages, so it does not babble a new word every tick. */
	private static final float URGE_PRESSURE = 0.8f;

	// --- #21: learnable coinage frequency -----------------------------------------
	// The old layer imposed ONE global cooldown (160 ticks) on every coinage — it never learned
	// WHICH moments it was overdue a name. Now coinage pacing is a statistic per salient anchor
	// (the signature of the current perceptual situation):
	//   * recurrence  — how often this anchor has been met while STILL nameless. A nameless moment
	//       that keeps coming back earns the right to be named sooner (its effective interval shrinks
	//       and its pressure is amplified); a moment met once waits, so it is not over-named.
	//   * stickiness  — once the word coined for this anchor actually binds strongly (its strength in
	//       the semantic layer rises), the anchor "has a word": further coinage for it is suppressed.
	//       This is the anti-over-naming term: a stuck anchor stops pressing, a still-nameless one
	//       keeps pressing, and the balance is learned from observed strength — never hard-coded.
	/** The old fixed cooldown, kept only as the starting interval a fresh anchor learns around. */
	private static final int BASE_INTERVAL = 160;
	/** No anchor waits less than this between coinages (hard floor, the global anti-babble). */
	private static final int MIN_INTERVAL = 40;
	/** Each recurrence of a nameless anchor shortens its wait by 1/(1 + n*0.25). */
	private static final float RECURRENCE_SPEEDUP = 0.25f;
	/** Each recurrence amplifies coinage pressure by (1 + min(n,24)*0.08). */
	private static final float RECURRENCE_PRESSURE = 0.08f;
	/** Recurrence amplifies pressure at most this much (cap, so a fixation does not coin every tick). */
	private static final int RECURRENCE_CAP = 24;
	/** Bound the derived anchor table; overflow just resets it (stats are cheap to relearn). */
	private static final int MAX_ANCHORS = 256;

	/** Per-anchor learning record (see the block above). */
	private static final class AnchorStat {
		int recurrence;
		long lastCoin = -1;
		String coinedWord;
		boolean stuck;
	}

	private final Map<String, AnchorStat> anchors = new HashMap<>();
	/** Internal clock: one tick per coinage decision (the voice already paces say-decisions). */
	private long now = 0;
	private int coined = 0;
	private String lastCoined = null;

	/**
	 * Advance one tick. Returns a freshly invented token when the brain has something salient to say
	 * but no learned word for it (and the cooldown has passed); otherwise {@code null}. The caller
	 * feeds the returned token through {@link SemanticLearner#learn}, so it binds to the current state.
	 *
	 * @param urge a present, active desire to speak. When true, the urge itself becomes pressure, so a
	 *        real wish to say something always ends in a fresh sound (subject to the cooldown) rather
	 *        than silence or a canned line.
	 */
	public String tick(Percept p, SemanticLearner s, Random r, boolean urge) {
		this.lastCoined = null; // only a coinage in *this* tick counts as fresh
		now++;
		if (p == null) {
			return null;
		}
		float pressure = referentialPressure(p, s);
		if (urge) {
			pressure = Math.max(pressure, URGE_PRESSURE);
		}

		String key = anchorKey(p);
		AnchorStat st = anchors.computeIfAbsent(key, k -> new AnchorStat());

		// This anchor met while nameless? Count the recurrence — that is the learning signal that
		// drives the dynamic frequency below.
		boolean nameless = pressure > 0f;
		if (nameless) {
			st.recurrence++;
		}
		// Stickiness check: did the word we already coined for THIS anchor actually bind? Once it
		// does, the anchor has a word and coinage pressure for it is suppressed (anti-over-naming).
		if (st.coinedWord != null && s != null) {
			float stg = s.wordStrength(st.coinedWord, p.featureList());
			if (stg >= HAVE_WORD) {
				st.stuck = true;
			}
		}
		if (st.stuck) {
			return null;
		}

		// Dynamic, per-anchor pacing: an anchor that just coined waits a full interval; an anchor
		// that keeps recurring nameless waits less (it is clearly begging for a name it does not
		// have). No global fixed cooldown — the wait is a learned function of recurrence.
		long elapsed = st.lastCoin < 0 ? Long.MAX_VALUE : now - st.lastCoin;
		int interval = (int) Math.max(MIN_INTERVAL,
				BASE_INTERVAL / (1.0 + st.recurrence * RECURRENCE_SPEEDUP));
		// An active urge (being addressed) answers with a fresh syllable even inside the anchor's
		// own interval, but never below the global floor — so a called baby babbles, not every tick.
		if (st.lastCoin >= 0 && elapsed < interval) {
			if (!urge || elapsed < MIN_INTERVAL) {
				return null;
			}
		}

		// Recurrence amplifies pressure: the more often this nameless moment comes back, the stronger
		// the urge to finally name it — capped so one fixation cannot coin unchecked.
		float dynPressure = pressure
				* (1.0f + Math.min(st.recurrence, RECURRENCE_CAP) * RECURRENCE_PRESSURE);
		if (dynPressure < PRESSURE_THRESHOLD) {
			return null;
		}

		String word = invent(r);
		st.lastCoin = now;
		st.coinedWord = word;
		st.recurrence = 0; // a coinage attempt resets the nameless count; if it never sticks and the
		                   // anchor keeps recurring, recurrence climbs again and it re-names itself.
		coined++;
		this.lastCoined = word;
		if (anchors.size() > MAX_ANCHORS) {
			anchors.clear(); // bounded table; statistics are cheap to relearn
		}
		return word;
	}

	/** A stable key for "this kind of moment": the sorted salient feature set, so the same situation
	 *  (same entity/structure/event signature) accumulates one learning record rather than N. */
	private String anchorKey(Percept p) {
		TreeMap<Integer, Boolean> sorted = new TreeMap<>();
		for (int f : p.features()) {
			if (isSalient(f)) {
				sorted.put(f, Boolean.TRUE);
			}
		}
		StringBuilder sb = new StringBuilder();
		for (Integer f : sorted.keySet()) {
			sb.append(f).append(',');
		}
		return sb.toString();
	}

	/** The token invented in the most recent tick, or {@code null} if none — lets the caller give a
	 *  freshly coined sound its own, stronger self-referential binding to the current state. */
	public String lastCoined() {
		return lastCoined;
	}

	/**
	 * How strongly the brain wants a word for the current moment: 0 when nothing salient is happening,
	 * rising toward 1 the more that is going on and the fewer learned words fit this state.
	 */
	public float referentialPressure(Percept p, SemanticLearner s) {
		if (p == null) {
			return 0f;
		}
		boolean salient = false;
		for (int f : p.features()) {
			if (isSalient(f)) {
				salient = true;
				break;
			}
		}
		if (!salient) {
			return 0f;
		}
		// the strongest learned word for this state: more learned → less pressure to coin
		float best = 0f;
		if (s != null) {
			for (String w : s.topWordsFor(p.featureList(), 8)) {
				float str = s.wordStrength(w, p.featureList());
				if (str > best) {
					best = str;
				}
			}
		}
		float lack = best >= HAVE_WORD ? 0f : 1f - (best / Math.max(HAVE_WORD, 1e-3f));
		return Math.max(0f, Math.min(1f, lack));
	}

	/** Draw a brand-new token of 1–4 letters from the closed alphabet, sometimes ending in an English
	 *  punctuation mark drawn from {@link #PUNCT} with probability {@link #PUNCT_CHANCE}. Punctuation is
	 *  treated exactly like a letter — the mouth can produce it, the semantic layer can bind it to a
	 *  state, and the brain may repeat it; no rule maps an intent to a mark. */
	private String invent(Random r) {
		int len = 1 + r.nextInt(MAX_LEN);
		StringBuilder sb = new StringBuilder(len + 4);
		for (int i = 0; i < len; i++) {
			sb.append(LETTERS.charAt(r.nextInt(LETTERS.length())));
		}
		if (r.nextFloat() < PUNCT_CHANCE) {
			sb.append(PUNCT[r.nextInt(PUNCT.length)]);
		}
		return sb.toString();
	}

	/** How many tokens the brain has invented so far (its vocabulary grew by this many). */
	public int coinedCount() {
		return coined;
	}

	/** A feature that names something in the world is worth pressing for a word for. */
	private static boolean isSalient(int f) {
		return (f >= Percept.F_ENTITY && f < Percept.F_ENTITY + 1000)
				|| (f >= Percept.F_SOUND && f < Percept.F_SOUND + 1000)
				|| (f >= Percept.F_STRUCTURE_BASE && f < Percept.F_STRUCTURE_BASE + 0x800)
				|| (f >= Percept.F_INTERACT_BLOCK_BASE && f < Percept.F_INTERACT_BLOCK_BASE + 0x800)
				|| (f >= Percept.F_INTERACT_ENTITY_BASE && f < Percept.F_INTERACT_ENTITY_BASE + 0x800)
				|| f == Percept.F_HURT || f == Percept.F_ON_FIRE || f == Percept.F_IN_LAVA
				|| (f >= Percept.F_SUBITIZE_BASE && f < Percept.F_SUBITIZE_BASE + 0x400)
				|| (f >= Percept.F_COUNT_BASE && f < Percept.F_COUNT_BASE + 0x40);
	}

	/**
	 * Persist the self-coined vocabulary learning state into {@code root}: the per-anchor coinage
	 * records, the internal clock and the coined count. Without this a world restart would wipe the
	 * words the bot invented for itself — breaking its self-invented language continuity.
	 */
	public void writeTo(JsonObject root) {
		JsonObject o = new JsonObject();
		o.addProperty("now", now);
		o.addProperty("coined", coined);
		JsonObject an = new JsonObject();
		for (Map.Entry<String, AnchorStat> e : anchors.entrySet()) {
			JsonObject s = new JsonObject();
			s.addProperty("recurrence", e.getValue().recurrence);
			s.addProperty("lastCoin", e.getValue().lastCoin);
			if (e.getValue().coinedWord != null) s.addProperty("word", e.getValue().coinedWord);
			s.addProperty("stuck", e.getValue().stuck);
			an.add(e.getKey(), s);
		}
		o.add("anchors", an);
		root.add("phonation", o);
	}

	/** Restore from {@code root}; missing/corrupt key leaves a fresh phonation (old saves). */
	public void readFrom(JsonObject root) {
		now = 0;
		coined = 0;
		anchors.clear();
		lastCoined = null;
		if (root == null || !root.has("phonation")) {
			return;
		}
		try {
			JsonObject o = root.getAsJsonObject("phonation");
			if (o.has("now")) now = o.get("now").getAsLong();
			if (o.has("coined")) coined = o.get("coined").getAsInt();
			if (o.has("anchors")) {
				for (Map.Entry<String, JsonElement> en : o.getAsJsonObject("anchors").entrySet()) {
					JsonObject s = en.getValue().getAsJsonObject();
					AnchorStat st = new AnchorStat();
					if (s.has("recurrence")) st.recurrence = s.get("recurrence").getAsInt();
					if (s.has("lastCoin")) st.lastCoin = s.get("lastCoin").getAsLong();
					if (s.has("word") && !s.get("word").isJsonNull()) st.coinedWord = s.get("word").getAsString();
					if (s.has("stuck")) st.stuck = s.get("stuck").getAsBoolean();
					anchors.put(en.getKey(), st);
				}
			}
		} catch (Exception e) {
			now = 0;
			coined = 0;
			anchors.clear();
		}
	}
}
