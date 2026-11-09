package stream.kleeamp.mobile.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource

class FolderImportTest {

    @Test fun primaryVolumeIdsResolveToStoragePaths() {
        assertEquals(
            "/storage/emulated/0/Music/Album",
            folderPathForDocumentId("primary:Music/Album", ::roots),
        )
        assertEquals("/storage/emulated/0", folderPathForDocumentId("primary:", ::roots))
    }

    @Test fun removableVolumeIdsKeepTheirUuidMount() {
        assertEquals(
            "/storage/1A2B-3C4D/Books/Book",
            folderPathForDocumentId("1A2B-3C4D:Books/Book", ::roots),
        )
    }

    @Test fun rawAndVirtualIdsResolveOnlyWhenTheyNameStorage() {
        assertEquals(
            "/storage/emulated/0/Download/Album",
            folderPathForDocumentId("raw:/storage/emulated/0/Download/Album", ::roots),
        )
        assertNull(folderPathForDocumentId("home:", ::roots))
        assertNull(folderPathForDocumentId("no-volume", ::roots))
    }

    @Test fun folderImportMatchesNestedLibraryRowsOnly() {
        val songs = listOf(
            song("/storage/emulated/0/Music/Album/01.mp3"),
            song("/storage/emulated/0/Music/Album/Live/02.mp3"),
            song("/storage/emulated/0/Music/AlbumTwo/03.mp3"),
            song("/storage/emulated/0/Podcasts/04.mp3"),
        )
        assertEquals(
            listOf(songs[0], songs[1]),
            songsUnder(songs, "/storage/emulated/0/Music/Album/"),
        )
    }

    private fun roots(uuid: String?): String? = when (uuid) {
        null -> "/storage/emulated/0"
        "1A2B-3C4D" -> "/storage/1A2B-3C4D"
        // Unknown ids (`home:`) belong to no storage volume.
        else -> null
    }

    private fun song(path: String) = Station(
        id = "local:$path",
        name = path.substringAfterLast('/'),
        url = "content://media/external/audio/media/1",
        source = StationSource.Local,
        durationMs = 1_000,
    )
}
