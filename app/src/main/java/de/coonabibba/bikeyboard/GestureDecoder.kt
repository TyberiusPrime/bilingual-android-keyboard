package de.coonabibba.bikeyboard

import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Turns a word into the path it would have been swiped along, and says how far
 * an actual swipe was from it (D39).
 *
 * This is the whole of the gesture model: no learning, no per-user calibration,
 * no smoothing beyond the resampling. What makes it work is not the matching
 * but what sits behind it — the same two lexicons, weighted by how common each
 * word is, so that a shape which fits `hello` and `helo` equally well resolves
 * to the one people actually write. Swipe typing is mostly a frequency problem
 * wearing a geometry problem's clothes, and the geometry only has to be good
 * enough to narrow the field.
 *
 * Characters with no key of their own are **skipped rather than refused**, which
 * is what lets `don't` be swiped as `dont`: the apostrophe lives on a long-press
 * (D17), so no finger ever travels to it, and a candidate that insisted on one
 * could never be reached.
 */
class GestureDecoder(private val keys: KeyGeometry) {

    /** Scratch for the candidate polyline, reused across a single decode pass. */
    private var polyX = FloatArray(INITIAL_POLYLINE)
    private var polyY = FloatArray(INITIAL_POLYLINE)

    /** Per-letter distance to the stroke, read off [keyDistance] (-1: unknown). */
    private var polyD = FloatArray(INITIAL_POLYLINE)

    /** Two rows of the alignment table, swapped rather than reallocated. */
    private val costRow = FloatArray(GesturePath.SAMPLES)
    private val nextRow = FloatArray(GesturePath.SAMPLES)

    /** Distance from each letter key to the stroke being decoded, worked out once. */
    private val keyDistance = FloatArray(26)
    private var prepared: GesturePath? = null

    /**
     * How far the finger would travel to swipe [word], in pixels, or
     * [UNSWIPEABLE] if the word has fewer than two distinct keys.
     *
     * Deliberately cheap — no allocation, one pass — because it is the filter
     * that keeps the expensive comparison off most of the dictionary. A word
     * whose journey is half or twice the length of the one actually made is not
     * a near miss, it is a different word.
     */
    fun idealLength(word: CharSequence): Float {
        var length = 0f
        var lastX = 0f
        var lastY = 0f
        var seen = 0
        for (index in word.indices) {
            val entry = keys.centreOf(word[index]) ?: continue
            if (seen > 0) {
                // A doubled letter is one place on the keyboard, not two. The
                // finger does not move for the second `l` of `hello`, so the
                // ideal path must not either.
                if (entry.centreX == lastX && entry.centreY == lastY) continue
                length += hypot(entry.centreX - lastX, entry.centreY - lastY)
            }
            lastX = entry.centreX
            lastY = entry.centreY
            seen++
        }
        return if (seen < 2) UNSWIPEABLE else length
    }

    /**
     * The path [word] would have been swiped along, or null if it has none.
     *
     * No longer what scoring uses — see [visitCost] — but still the honest
     * picture of a word as a journey, and what the tests draw comparisons
     * against.
     */
    fun idealPath(word: CharSequence): GesturePath? {
        val count = letterCentres(word)
        if (count < 2) return null
        return GesturePath.of(polyX, polyY, count)
    }

    /**
     * How far [path] is from the way [word] should have been swiped, in key
     * widths, or [UNSWIPEABLE] if the word cannot be swiped at all.
     *
     * The number is on the same footing as a spatial edit distance
     * ([SpatialEditDistance]) on purpose: both say "how implausible is it that
     * this finger meant this word", both are fed to the same exponential, and
     * both therefore land on the one confidence scale the strip's purple and
     * the auto-replace threshold read (D33, D37).
     */
    fun cost(path: GesturePath, word: CharSequence): Float {
        if (keys.keyWidth <= 0f) return UNSWIPEABLE
        // Before the letters are gathered, not after: they read their distances
        // straight out of the table this fills.
        prepare(path)

        val letters = letterCentres(word)
        if (letters < 2 || letters > GesturePath.SAMPLES) return UNSWIPEABLE

        // Cheap first, and exactly cheap enough: a candidate whose letters were
        // never approached is refused before either of the real terms runs.
        if (lowerBound(letters) > MAX_COST) return UNSWIPEABLE

        val visit = sqrt(visitCost(path, letters) / letters) / keys.keyWidth
        val coverage = sqrt(coverageCost(path, letters)) / keys.keyWidth
        return visit + coverage
    }

    /**
     * A floor under what [cost] would say, for a fraction of the work.
     *
     * The search uses this to decide which candidates are worth scoring
     * properly — see `DictionarySuggestions.PRUNE_RATIO`.
     */
    fun bound(path: GesturePath, word: CharSequence): Float {
        if (keys.keyWidth <= 0f) return UNSWIPEABLE
        prepare(path)
        val letters = letterCentres(word)
        if (letters < 2 || letters > GesturePath.SAMPLES) return UNSWIPEABLE
        return lowerBound(letters)
    }

    /**
     * A floor under [cost] that costs almost nothing to work out.
     *
     * There are only twenty-six places a letter can be, so the distance from
     * each key to the stroke is worked out **once per swipe** and then read off
     * by every one of the several hundred candidates. Ignoring the order the
     * letters have to come in can only make the answer smaller, and coverage is
     * never negative, so this is a genuine lower bound on the full cost — a
     * candidate it refuses would have been refused anyway. Nothing is decided
     * differently; the arithmetic simply does not happen.
     *
     * Worth doing because the alternative is not free. A stroke is decoded once
     * per word rather than once per keystroke, which bought room for the two
     * terms that made swiping work — and then spent it: the pair cost about ten
     * times what the first attempt did. A phone runs on a battery, and several
     * hundred candidates each getting two dynamic programmes is a poor way to
     * discover that most of them start with the wrong letters.
     */
    private fun lowerBound(letters: Int): Float {
        var total = 0f
        for (i in 0 until letters) {
            val d = polyD[i]
            // A letter off the alphabet has no precomputed distance, so there
            // is no bound to apply and the candidate goes the long way round.
            if (d < 0f) return 0f
            total += d * d
        }
        return sqrt(total / letters) / keys.keyWidth
    }

    /**
     * Measures every key against this stroke, once.
     *
     * Twenty-six keys against forty-seven segments is about the work of three
     * candidates, and it is spent instead of the work of hundreds.
     */
    private fun prepare(path: GesturePath) {
        if (prepared === path) return
        prepared = path
        for (index in keyDistance.indices) {
            val entry = keys.centreOf('a' + index)
            keyDistance[index] = if (entry == null) {
                Float.MAX_VALUE
            } else {
                nearestOnPath(path, entry.centreX, entry.centreY)
            }
        }
    }

    private fun nearestOnPath(path: GesturePath, x: Float, y: Float): Float {
        var best = Float.MAX_VALUE
        for (j in 0 until path.xs.size - 1) {
            val d = distanceToSegment(x, y, path.xs[j], path.ys[j], path.xs[j + 1], path.ys[j + 1])
            if (d < best) best = d
        }
        return best
    }

    /**
     * How much of the stroke the word fails to account for: the mean distance
     * from each point of the stroke to the nearest point of the word's route.
     *
     * The other half of the question, and the half that was missing. [visitCost]
     * asks whether the finger went where the word needed it to; this asks
     * whether the word explains where the finger actually went. Neither is
     * sufficient alone — a short word satisfies the first trivially by having
     * few letters to visit, and a rambling one satisfies the second by having a
     * route that covers everything — and together they are hard to cheat.
     *
     * This is the term that decides `learning` against `laughing` on the stroke
     * that prompted it. Both begin `l` and end `g`, both are the right sort of
     * length, and `laughing` is six times the commoner — but the finger plainly
     * went up to `e` and out to `r`, and `laughing`'s route passes nowhere near
     * either. Nothing charged it for that before.
     *
     * **Alignment-free on purpose**, which is the other repair. Distance to the
     * nearest point of the route does not care *when* the finger was there, so
     * a loop where somebody swung wide and came back costs almost nothing — the
     * loop stays close to the route it is looping around. Comparing the two
     * paths position by position instead, as the first version did, made a loop
     * catastrophic: the extra travel shifted every later sample against its
     * counterpart, so the correct word's cost rose from nothing to 0.79 while
     * the wrong word — already misaligned, with nothing left to lose — barely
     * moved. It punished exactly the word it should have been finding.
     */
    private fun coverageCost(path: GesturePath, letters: Int): Float {
        var total = 0f
        for (j in path.xs.indices) {
            var best = Float.MAX_VALUE
            for (i in 0 until letters - 1) {
                val d = distanceToSegment(
                    path.xs[j], path.ys[j],
                    polyX[i], polyY[i],
                    polyX[i + 1], polyY[i + 1],
                )
                if (d < best) best = d
            }
            total += best * best
        }
        return total / path.xs.size
    }

    /** Distance from a point to the line segment between two key centres. */
    private fun distanceToSegment(
        px: Float,
        py: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
    ): Float {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 0f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /**
     * How far the finger strayed from the letters it had to visit, in order.
     *
     * **This asks a different question from comparing the two paths point for
     * point, and the difference is the whole of what went wrong first time.**
     * Matching by position along the stroke charges full price for the *route*
     * between two letters: swiping `laughing`, the finger left `a` and arced up
     * over `e` and `r` on its way to `u` instead of taking the straight
     * diagonal, and every sample of that arc was scored against a straight line
     * a key and a half below it. The word was thrown out entirely — 5th
     * commonest of the 147 words that begin with `l` and end with `g`, and not
     * offered at all.
     *
     * What actually identifies a word is that the stroke **passes near its
     * letters in the right order**. So each letter is matched to its own point
     * on the stroke, the matches must advance, and every point in between costs
     * nothing — it is travel, and how the finger chose to travel is not
     * evidence about anything.
     *
     * This is the model rejected when the decoder was first written, on the
     * grounds that swiping `hello` crosses `r`, `t`, `y`, `d`, `f`, `g` and `j`
     * and something would have to explain them away. That objection confused
     * two things: matching every *point* to a letter, which is indeed hopeless,
     * and matching every *letter* to a point, which is this — and which says
     * nothing whatever about the keys passed over on the way.
     *
     * Both ends are pinned, first letter to first point and last to last, which
     * is the same assumption the search's endpoints already rest on. Without it
     * a word could be found entirely within the first half of a stroke and the
     * rest ignored, and every short word would beat every long one.
     */
    private fun visitCost(path: GesturePath, letters: Int): Float {
        // Letters are placed on *segments* of the stroke rather than on its
        // sample points. Measuring to the nearest sample sounds equivalent and
        // is not: samples sit a whole key apart on a long word, so the distance
        // from a letter to the nearest one has a floor of half a key however
        // perfectly the word was traced. Measuring to the nearest point on the
        // stroke itself removes a floor that had nothing to do with the typist.
        val segments = path.xs.size - 1
        var previous = costRow
        var current = nextRow

        // The first letter is where the finger came down, so it has exactly one
        // place it can be.
        previous[0] = gap(path, 0, 0)
        for (j in 1 until segments) previous[j] = Float.MAX_VALUE

        for (letter in 1 until letters) {
            current[0] = Float.MAX_VALUE
            // The best way to have placed every earlier letter strictly before
            // this segment, carried along as the row is filled.
            var best = previous[0]
            for (j in 1 until segments) {
                if (previous[j - 1] < best) best = previous[j - 1]
                current[j] = if (best == Float.MAX_VALUE) {
                    Float.MAX_VALUE
                } else {
                    best + gap(path, letter, j)
                }
            }
            val swap = previous
            previous = current
            current = swap
        }

        // And the last letter is where the finger lifted.
        return previous[segments - 1]
    }

    /**
     * *Squared* distance from the [letter]th key centre to segment [j] of the
     * stroke.
     *
     * Squared because a word is wrong if **any** of its letters was never
     * approached, and a plain mean hides that: `leaving` misses `v` by nearly
     * two key widths on a stroke that spells `learning`, and averaged across
     * seven letters that single miss all but vanished. Squaring makes one large
     * gap cost far more than several small ones, which is the shape of the
     * question actually being asked.
     */
    private fun gap(path: GesturePath, letter: Int, j: Int): Float {
        val d = distanceToSegment(
            polyX[letter], polyY[letter],
            path.xs[j], path.ys[j],
            path.xs[j + 1], path.ys[j + 1],
        )
        return d * d
    }

    /**
     * Fills the scratch polyline with [word]'s key centres and says how many
     * there are. Consecutive repeats collapse: the finger does not move for the
     * second `l` of `hello`, so there is nothing for that letter to visit that
     * the first one has not already.
     */
    private fun letterCentres(word: CharSequence): Int {
        if (polyX.size < word.length) {
            polyX = FloatArray(word.length)
            polyY = FloatArray(word.length)
            polyD = FloatArray(word.length)
        }
        var count = 0
        for (index in word.indices) {
            val entry = keys.centreOf(word[index]) ?: continue
            if (count > 0 && entry.centreX == polyX[count - 1] && entry.centreY == polyY[count - 1]) {
                continue
            }
            polyX[count] = entry.centreX
            polyY[count] = entry.centreY
            // Carried alongside so the cheap bound needs no geometry at all.
            val letter = Folding.foldChar(word[index]) - 'a'
            polyD[count] = if (letter in keyDistance.indices) keyDistance[letter] else -1f
            count++
        }
        return count
    }

    /**
     * What a stroke is allowed to be and still be considered.
     *
     * These live with the decoder rather than with the search that applies
     * them, because every one of them is a statement about fingers rather than
     * about dictionaries — and because a gate that silently rejects the right
     * word is the hardest kind of failure to see from outside. `GestureGateTest`
     * reads them to report which one is doing the rejecting, which it cannot do
     * if they are shut away.
     */
    companion object {
        /** A word no finger could have traced: fewer than two keys on the way. */
        const val UNSWIPEABLE = Float.MAX_VALUE

        /**
         * How far off a key the finger may come down or lift and still have
         * that key considered, in key widths.
         *
         * Tighter than tapping's `FIRST_LETTER_REACH`, and for a reason about
         * cost rather than accuracy: the endpoints multiply. Three plausible
         * first letters and three plausible last ones is nine slices of
         * dictionary, not three — generosity here is quadratic where everywhere
         * else in the search it is linear.
         */
        const val ENDPOINT_REACH = 0.35f

        /** A hard ceiling on that multiplication, per endpoint. */
        const val MAX_ENDPOINT_LETTERS = 3

        /**
         * How far the journey's length may differ from the candidate's.
         *
         * Wide, and widened again after measuring. People cut corners hard, so
         * a real path runs *shorter* than the ideal one — on a long word with
         * several reversals it can be less than half of it — while a wobbling
         * thumb makes it longer. Both were rejecting correct words outright at
         * the first setting, which is the worst way to fail: the word is gone
         * before anything scores it, so it cannot even appear in the strip as a
         * runner-up. What this gate is really for is throwing out `an` when the
         * finger crossed the whole keyboard.
         */
        const val MIN_LENGTH_RATIO = 0.3f
        const val MAX_LENGTH_RATIO = 3.2f

        /**
         * The worst average deviation, in key widths, still worth ranking.
         *
         * Measured against real-shaped strokes rather than picked: a hurried
         * one — corners cut to nothing, thumb wandering half a key, sampled
         * once a frame — runs to about 1.2, and a ceiling of 1.0 was cutting
         * those off. The scorer already discounts a poor fit exponentially, so
         * a loose gate costs a little time and no accuracy, while a tight one
         * costs the word.
         */
        const val MAX_COST = 1.8f

        private const val INITIAL_POLYLINE = 32
    }
}
