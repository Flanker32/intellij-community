data class Data(val i: Int) {}

fun usage(d: Data) {
    d.<caret>component1()
}
// IGNORE_K2