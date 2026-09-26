import Foundation

/// A compact fuzzy matcher in the style of fzf/iTerm: the query characters
/// must appear in order (as a subsequence) in the haystack, and a LOWER score
/// is better. Consecutive runs are cheaper than spread-out matches, and a
/// leading or word-boundary match is cheaper than mid-word, so "aln" ranks
/// above "ana" when searching for "Alan Walker". Ported from Android's
/// `Fuzzy.kt`, matching case-folded Unicode scalars so composed characters
/// ("İstanbul") behave like the Kotlin code-unit comparison.
public enum Fuzzy {
    /// Scalar positions in `haystack` that form the match, or nil when no
    /// match. Mirrors Android's `match`.
    public static func match(query: String, haystack: String) -> [Int]? {
        guard !query.isEmpty else { return [] }
        let q = Array(query.lowercased().unicodeScalars)
        let h = Array(haystack.lowercased().unicodeScalars)
        guard q.count <= h.count else { return nil }

        var at: [Int] = []
        at.reserveCapacity(q.count)
        var qi = 0
        for ci in h.indices where qi < q.count {
            if h[ci] == q[qi] {
                at.append(ci)
                qi += 1
            }
        }
        return qi == q.count ? at : nil
    }

    /// Lower is better. `Int.max` means no match.
    public static func score(query: String, haystack: String) -> Int {
        guard let at = match(query: query, haystack: haystack) else { return Int.max }
        guard !at.isEmpty else { return 0 }
        let scalars = Array(haystack.lowercased().unicodeScalars)
        var total = 0
        var previous = -2
        for (index, position) in at.enumerated() {
            if index > 0, position == previous + 1 {
                // Consecutive characters are free.
            } else {
                total += position - previous // gap cost
            }
            previous = position
        }
        if at[0] == 0 { total -= 4 } // reward a prefix match
        if at[0] > 0 {
            let before = scalars[at[0] - 1]
            if before == " " || before == "-" || before == "(" { total -= 2 }
        }
        if scalars.count <= 12 { total -= 1 }
        return total
    }

    /// Character positions that matched, for highlight rendering. Folding can
    /// expand a character ("İ" lowercases to i plus a combining dot), so the
    /// folded scalar sequence is mapped back to the original character that
    /// produced each scalar.
    public static func matchedPositions(query: String, haystack: String) -> Set<Int>? {
        guard let scalarHits = match(query: query, haystack: haystack) else { return nil }
        var owner: [Int] = [] // folded scalar index -> original character index
        for (characterIndex, character) in haystack.enumerated() {
            for _ in character.lowercased().unicodeScalars {
                owner.append(characterIndex)
            }
        }
        var result = Set<Int>()
        for hit in scalarHits where owner.indices.contains(hit) {
            result.insert(owner[hit])
        }
        return result
    }
}
