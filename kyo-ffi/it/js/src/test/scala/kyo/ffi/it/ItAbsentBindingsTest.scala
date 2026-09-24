package kyo.ffi.it

import kyo.ffi.Ffi
import kyo.ffi.FfiLoadError

class ItAbsentBindingsTest extends Test:

    "Ffi.load of a binding whose library is absent throws LibraryNotFound, not a binding that fails at its first call" in {
        val ex = intercept[FfiLoadError.LibraryNotFound](Ffi.load[ItAbsentBindings])
        assert(ex.getMessage.contains("kyo_it_absent"))
    }

    "a second Ffi.load of the same absent binding throws the same LibraryNotFound" in {
        val first  = intercept[FfiLoadError.LibraryNotFound](Ffi.load[ItAbsentBindings])
        val second = intercept[FfiLoadError.LibraryNotFound](Ffi.load[ItAbsentBindings])
        assert(second eq first)
    }

end ItAbsentBindingsTest
