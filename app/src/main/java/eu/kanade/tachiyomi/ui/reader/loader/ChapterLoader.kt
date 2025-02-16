package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import com.github.junrar.exception.UnsupportedRarV5Exception
import eu.kanade.tachiyomi.data.download.manga.MangaDownloadManager
import eu.kanade.tachiyomi.data.download.manga.MangaDownloadProvider
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import tachiyomi.core.i18n.stringResource
import tachiyomi.core.storage.UniFileTempFileManager
import tachiyomi.core.util.lang.withIOContext
import tachiyomi.core.util.system.logcat
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.source.manga.model.StubMangaSource
import tachiyomi.i18n.MR
import tachiyomi.source.local.entries.manga.LocalMangaSource
import tachiyomi.source.local.io.Format
import uy.kohesive.injekt.injectLazy

/**
 * Loader used to retrieve the [PageLoader] for a given chapter.
 */
class ChapterLoader(
    private val context: Context,
    private val downloadManager: MangaDownloadManager,
    private val downloadProvider: MangaDownloadProvider,
    private val tempFileManager: UniFileTempFileManager,
    private val manga: Manga,
    private val source: MangaSource,
) {

    private val readerPreferences: ReaderPreferences by injectLazy()

    /**
     * Assigns the chapter's page loader and loads the its pages. Returns immediately if the chapter
     * is already loaded.
     */
    suspend fun loadChapter(chapter: ReaderChapter, page: Int? = null) {
        if (chapterIsReady(chapter)) {
            return
        }

        chapter.state = ReaderChapter.State.Loading
        withIOContext {
            logcat { "Loading pages for ${chapter.chapter.name}" }
            try {
                val loader = getPageLoader(chapter)
                chapter.pageLoader = loader

                val pages = loader.getPages()
                    .onEach { it.chapter = chapter }

                if (pages.isEmpty()) {
                    throw Exception(context.stringResource(MR.strings.page_list_empty_error))
                }

                // If the chapter is partially read, set the starting page to the last the user read
                // otherwise use the requested page.
                if (!chapter.chapter.read || readerPreferences.preserveReadingPosition().get() || page != null) {
                    chapter.requestedPage = page ?: chapter.chapter.last_page_read
                }

                chapter.state = ReaderChapter.State.Loaded(pages)
            } catch (e: Throwable) {
                chapter.state = ReaderChapter.State.Error(e)
                throw e
            }
        }
    }

    /**
     * Checks [chapter] to be loaded based on present pages and loader in addition to state.
     */
    private fun chapterIsReady(chapter: ReaderChapter): Boolean {
        return chapter.state is ReaderChapter.State.Loaded && chapter.pageLoader != null
    }

    /**
     * Returns the page loader to use for this [chapter].
     */
    private fun getPageLoader(chapter: ReaderChapter): PageLoader {
        val dbChapter = chapter.chapter
        val isDownloaded = downloadManager.isChapterDownloaded(
            chapterName = dbChapter.name,
            chapterScanlator = dbChapter.scanlator,
            mangaTitle = manga.ogTitle,
            sourceId = manga.source,
            skipCache = true,
        )
        return when {
            isDownloaded -> DownloadPageLoader(
                chapter,
                manga,
                source,
                downloadManager,
                downloadProvider,
                tempFileManager,
            )
            source is LocalMangaSource -> source.getFormat(chapter.chapter).let { format ->
                when (format) {
                    is Format.Directory -> DirectoryPageLoader(format.file)
                    is Format.Zip -> ZipPageLoader(tempFileManager.createTempFile(format.file))
                    is Format.Rar -> try {
                        RarPageLoader(tempFileManager.createTempFile(format.file))
                    } catch (e: UnsupportedRarV5Exception) {
                        error(context.stringResource(MR.strings.loader_rar5_error))
                    }
                    is Format.Epub -> EpubPageLoader(tempFileManager.createTempFile(format.file))
                }
            }
            source is HttpSource -> HttpPageLoader(chapter, source)
            source is StubMangaSource -> error(
                context.stringResource(MR.strings.source_not_installed, source.toString()),
            )
            else -> error(context.stringResource(MR.strings.loader_not_implemented_error))
        }
    }
}
