package com.seamlessplayer.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Odtwarzacz multimedialny — odpowiednik player.py / playerMute.py
 *
 * Funkcje:
 * - Wybór katalogu z plikami (SAF)
 * - Odtwarzanie plików jeden po drugim (ExoPlayer)
 * - Zapętlanie / losowe odtwarzanie
 * - Pełny ekran z automatycznym ukrywaniem kontrolek
 * - Muzyka w tle (drugi ExoPlayer)
 * - Tryb wyciszony (wideo bez dźwięku)
 * - Przejdź do wybranego pliku
 * - Sortowanie naturalne lub lokalne (jak Windows)
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SORT_MODE = "sort_mode"
        const val EXTRA_MUTED = "muted"
        const val SORT_NATURAL = 0
        const val SORT_LOCALE = 1

        private val MEDIA_EXTENSIONS = setOf(
            "mp3", "mp4", "avi", "mkv", "flv", "wmv",
            "mov", "m4v", "flac", "wav", "ogg", "aac",
            "wma", "m4a", "webm", "3gp", "ts", "m2ts"
        )

        private const val HIDE_CONTROLS_DELAY = 2000L

        // --- Bufor / prefetch: tu naprawiamy dlawienie przy zmianie pliku ---
        // Min. ile ms trzyma w buforze zanim zacznie odtwarzac / kontynuowac po rebufferze
        private const val MIN_BUFFER_MS = 30_000
        // Max. ile ms moze zbuforowac z wyprzedzeniem (kolejny plik tez sie tu liczy)
        private const val MAX_BUFFER_MS = 60_000
        // Ile ms bufora wymagane by ZACZAC odtwarzanie pierwszego/po seeku
        private const val BUFFER_FOR_PLAYBACK_MS = 1_500
        // Ile ms bufora wymagane by wznowic PO rebufferingu (mniejsze = szybszy powrot)
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000
        // Ile plikow w przod prefetchowac do pamieci/cache przed tym jak ExoPlayer ich zazada
        private const val PREFETCH_AHEAD_COUNT = 2
        // Ile bajtow z kazdego pliku wstepnie wczytac (analogicznie do PrefetchWorker w pythonie)
        private const val PREFETCH_CHUNK_BYTES = 512 * 1024
    }

    // --- Player ---
    private lateinit var player: ExoPlayer
    private var audioPlayer: ExoPlayer? = null

    // --- UI ---
    private lateinit var playerView: PlayerView
    private lateinit var controlsPanel: View
    private lateinit var infoLabel: TextView
    private lateinit var directoryLabel: TextView
    private lateinit var audioLabel: TextView
    private lateinit var currentFileLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var btnPlayPause: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnFullscreen: MaterialButton
    private lateinit var btnLoop: MaterialButton
    private lateinit var btnRandom: MaterialButton
    private lateinit var btnGoto: MaterialButton
    private lateinit var btnSelectDir: MaterialButton
    private lateinit var btnSelectAudio: MaterialButton

    // --- State ---
    private var mediaFiles = mutableListOf<Uri>()
    private var mediaNames = mutableListOf<String>()
    private var isLooping = false
    private var isRandomMode = false
    private var isMuted = false
    private var sortMode = SORT_NATURAL
    private var isFullscreen = false
    private var controlsVisible = true
    private var directoryName: String? = null
    private var backgroundAudioUri: Uri? = null

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }

    // --- Prefetch (analogiczne do PrefetchWorker / rolling prefetch w player_mpv.py) ---
    private val prefetchExecutor = Executors.newSingleThreadExecutor()
    private val prefetchedIndices = mutableSetOf<Int>()

    // --- Activity Result Launchers ---
    private val pickDirectory =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { onDirectoryPicked(it) }
        }

    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { onAudioPicked(it) }
        }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        // Odczytaj parametry z Intentu
        sortMode = intent.getIntExtra(EXTRA_SORT_MODE, SORT_NATURAL)
        isMuted = intent.getBooleanExtra(EXTRA_MUTED, false)

        // Tytuł okna
        title = when {
            isMuted -> getString(R.string.title_muted)
            sortMode == SORT_LOCALE -> getString(R.string.title_windows)
            else -> getString(R.string.title_standard)
        }

        initViews()
        initPlayer()
        initGestureDetector()

        // Status początkowy
        statusLabel.text = if (isMuted) getString(R.string.ready_muted) else getString(R.string.ready)
        updateInfoLabel()
        updateFileCounter()
    }

    override fun onPause() {
        super.onPause()
        // Zatrzymaj odtwarzanie gdy aktywność jest w tle
        player.pause()
        audioPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideHandler.removeCallbacks(hideRunnable)
        playHandler.removeCallbacksAndMessages(null)
        prefetchExecutor.shutdownNow()
        player.release()
        audioPlayer?.release()
    }

    // =========================================================================
    // Initialization
    // =========================================================================

    private fun initViews() {
        playerView = findViewById(R.id.playerView)
        controlsPanel = findViewById(R.id.controlsPanel)
        infoLabel = findViewById(R.id.infoLabel)
        directoryLabel = findViewById(R.id.directoryLabel)
        audioLabel = findViewById(R.id.audioLabel)
        currentFileLabel = findViewById(R.id.currentFileLabel)
        statusLabel = findViewById(R.id.statusLabel)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnStop = findViewById(R.id.btnStop)
        btnFullscreen = findViewById(R.id.btnFullscreen)
        btnLoop = findViewById(R.id.btnLoop)
        btnRandom = findViewById(R.id.btnRandom)
        btnGoto = findViewById(R.id.btnGoto)
        btnSelectDir = findViewById(R.id.btnSelectDir)
        btnSelectAudio = findViewById(R.id.btnSelectAudio)

        btnPlayPause.setOnClickListener { togglePlayPause() }
        btnStop.setOnClickListener { stopPlayback() }
        btnFullscreen.setOnClickListener { toggleFullscreen() }
        btnLoop.setOnClickListener { toggleLoop() }
        btnRandom.setOnClickListener { toggleRandom() }
        btnGoto.setOnClickListener { showGotoDialog() }
        btnSelectDir.setOnClickListener { pickDirectory.launch(null) }
        btnSelectAudio.setOnClickListener {
            pickAudio.launch(arrayOf("audio/*"))
        }
    }

    @OptIn(UnstableApi::class)
    private fun initPlayer() {
        // Wlasny LoadControl - domyslny ExoPlayer ma zbyt male bufory, dlatego
        // przy przejsciu na kolejny plik (zwlaszcza z SAF/ContentResolver, czyli
        // wolniejszy odczyt niz lokalny plik) brakuje czasu na zbuforowanie i
        // wystepuje dlawienie. Wieksze MIN/MAX_BUFFER_MS daje ExoPlayerowi wiecej
        // miejsca by buforowac kolejny element z playlisty z wyprzedzeniem.
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, 16 * 1024))
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            // Daje wiecej czasu dekoderowi na "join" przy zmianie pliku zamiast
            // ucinac/zrzucac klatki kiedy nastepny plik nie jest jeszcze w 100% gotowy
            .setSeekBackIncrementMs(5_000)
            .setSeekForwardIncrementMs(5_000)
            .build()
        playerView.player = player

        // ExoPlayer ma wbudowany prefetch nastepnego elementu playlisty, ale trzeba
        // mu na to dac szanse - foreground mode trzyma zasoby gotowe nawet gdy
        // aktywnosc nie jest w pierwszym planie renderowania
        player.setForegroundMode(true)

        // Wycisz wideo jeśli tryb bez dźwięku
        if (isMuted) {
            player.volume = 0f
        }

        // Listener na zmiany stanu
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        statusLabel.text = getString(R.string.stopped)
                        btnPlayPause.text = getString(R.string.play)
                    }
                    Player.STATE_READY -> {
                        if (player.isPlaying) {
                            statusLabel.text = if (isMuted) getString(R.string.playing_muted)
                                                else getString(R.string.playing)
                        }
                    }
                    Player.STATE_BUFFERING -> {
                        statusLabel.text = "Buforowanie…"
                    }
                    Player.STATE_IDLE -> {
                        statusLabel.text = getString(R.string.ready)
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    btnPlayPause.text = getString(R.string.pause)
                    statusLabel.text = if (isMuted) getString(R.string.playing_muted)
                                        else getString(R.string.playing)
                } else {
                    if (player.playbackState != Player.STATE_ENDED) {
                        btnPlayPause.text = getString(R.string.resume)
                        statusLabel.text = getString(R.string.paused)
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateFileCounter()
                val idx = player.currentMediaItemIndex
                if (idx in mediaNames.indices) {
                    val name = mediaNames[idx]
                    statusLabel.text = if (isMuted) "▶ $name (bez dźwięku)" else "▶ $name"
                }
                // Rolling prefetch - przy kazdym przejsciu doladuj kolejne pliki w przod,
                // tak jak _rolling_prefetch w player_mpv.py
                prefetchAhead(idx)
            }
        })
    }

    /**
     * Wczytuje poczatkowe bajty z najblizszych plikow w przod, zeby trafily do
     * cache systemowego/ContentResolvera przed tym jak ExoPlayer ich zazada.
     * Odpowiednik PrefetchWorker / _rolling_prefetch z player_mpv.py.
     */
    private fun prefetchAhead(fromIndex: Int) {
        val end = minOf(fromIndex + 1 + PREFETCH_AHEAD_COUNT, mediaFiles.size)
        for (i in (fromIndex + 1) until end) {
            if (i in prefetchedIndices) continue
            prefetchedIndices.add(i)
            val uri = mediaFiles[i]
            prefetchExecutor.execute {
                try {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val buf = ByteArray(64 * 1024)
                        var readTotal = 0
                        while (readTotal < PREFETCH_CHUNK_BYTES) {
                            val n = stream.read(buf)
                            if (n <= 0) break
                            readTotal += n
                        }
                    }
                } catch (_: Exception) {
                    // Najlepszy wysilek - jesli sie nie uda, ExoPlayer i tak zaladuje plik normalnie
                }
            }
        }
    }

    private fun initGestureDetector() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (controlsVisible) {
                    // Ukryj od razu
                    hideControls()
                } else {
                    // Pokaż na 2 sekundy
                    showControlsTemporarily()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Podwójne kliknięcie przełącza pełny ekran
                toggleFullscreen()
                return true
            }
        })

        playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    // =========================================================================
    // Directory & Files
    // =========================================================================

    private fun onDirectoryPicked(treeUri: Uri) {
        // Zachowaj uprawnienia do odczytu
        try {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Nie zawsze możliwe — kontynuuj
        }

        val docFile = DocumentFile.fromTreeUri(this, treeUri)
        if (docFile == null) {
            Toast.makeText(this, getString(R.string.error), Toast.LENGTH_SHORT).show()
            return
        }

        directoryName = docFile.name ?: "?"

        // Znajdź pliki multimedialne
        val files = docFile.listFiles().filter { file ->
            file.isFile && file.name != null && isMediaFile(file.name!!)
        }

        if (files.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_media_found), Toast.LENGTH_LONG).show()
            return
        }

        // Sortuj pliki
        val sorted = sortFiles(files)

        // Załaduj do playera
        loadMedia(sorted)
    }

    private fun isMediaFile(filename: String): Boolean {
        val ext = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in MEDIA_EXTENSIONS
    }

    private fun sortFiles(files: List<DocumentFile>): List<DocumentFile> {
        return when (sortMode) {
            SORT_LOCALE -> {
                // Sortowanie jak w Windows — Collator z bieżącym locale
                val collator = Collator.getInstance()
                files.sortedWith(compareBy(collator) { it.name ?: "" })
            }
            else -> {
                // Sortowanie naturalne: 1, 2, 10 (nie 1, 10, 2)
                files.sortedWith(compareBy { naturalSortKey(it.name ?: "") })
            }
        }
    }

    /**
     * Klucz sortowania naturalnego — uwzględnia liczby w nazwie pliku.
     * Przykład: "video2.mp4" < "video10.mp4"
     */
    private fun naturalSortKey(filename: String): NaturalSortKey {
        val lower = filename.lowercase(Locale.ROOT)
        val parts = mutableListOf<Comparable<*>>()
        val regex = Regex("(\\d+)")
        var lastEnd = 0

        for (match in regex.findAll(lower)) {
            // Tekst przed liczbą
            if (match.range.first > lastEnd) {
                parts.add(lower.substring(lastEnd, match.range.first))
            }
            // Liczba — porównywana jako Long
            parts.add(match.value.toLongOrNull() ?: 0L)
            lastEnd = match.range.last + 1
        }
        // Reszta tekstu
        if (lastEnd < lower.length) {
            parts.add(lower.substring(lastEnd))
        }
        return NaturalSortKey(parts)
    }

    private class NaturalSortKey(private val parts: List<Comparable<*>>) : Comparable<NaturalSortKey> {
        override fun compareTo(other: NaturalSortKey): Int {
            for (i in 0 until minOf(parts.size, other.parts.size)) {
                val a = parts[i]
                val b = other.parts[i]
                @Suppress("UNCHECKED_CAST")
                val cmp = when {
                    a is String && b is String -> a.compareTo(b)
                    a is Long && b is Long -> a.compareTo(b)
                    a is String -> -1  // tekst przed liczbą
                    else -> 1
                }
                if (cmp != 0) return cmp
            }
            return parts.size.compareTo(other.parts.size)
        }
    }

    private fun loadMedia(files: List<DocumentFile>) {
        // Zatrzymaj obecne odtwarzanie
        player.stop()
        player.clearMediaItems()
        prefetchedIndices.clear()

        mediaFiles.clear()
        mediaNames.clear()

        // Opcjonalnie wymieszaj
        val orderedFiles = if (isRandomMode) files.shuffled() else files

        for (file in orderedFiles) {
            val uri = file.uri
            mediaFiles.add(uri)
            mediaNames.add(file.name ?: "?")
        }

        // Ustaw MediaItems
        val mediaItems = mediaFiles.map { MediaItem.fromUri(it) }
        player.setMediaItems(mediaItems)

        // Tryb powtarzania
        player.repeatMode = if (isLooping) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF

        // Przygotuj
        player.prepare()

        // Wystartuj prefetch pierwszych plikow od razu, jeszcze przed odtwarzaniem
        // (odpowiednik _start_initial_prefetch w player_mpv.py)
        prefetchAhead(-1)

        // Aktualizuj UI
        directoryLabel.text = getString(R.string.directory_format, directoryName ?: "?")
        updateInfoLabel()
        updateFileCounter()
        statusLabel.text = getString(R.string.loaded_files_format, mediaFiles.size)
        btnPlayPause.text = getString(R.string.play)

        // Pokaż pasek kontrolek od razu (bez auto-play)
        showControls()
        hideHandler.removeCallbacks(hideRunnable)
    }

    // =========================================================================
    // Background Audio
    // =========================================================================

    private fun onAudioPicked(uri: Uri) {
        // Zachowaj uprawnienia
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {}

        backgroundAudioUri = uri

        // Stwórz / odśwież odtwarzacz audio
        audioPlayer?.release()
        val ap = ExoPlayer.Builder(this).build()
        ap.volume = 0.5f // 50% głośności
        ap.repeatMode = Player.REPEAT_MODE_ONE // Zapętlaj audio

        val mediaItem = MediaItem.fromUri(uri)
        ap.setMediaItem(mediaItem)
        ap.prepare()

        audioPlayer = ap

        // Pobierz nazwę pliku
        val filename = getFilenameFromUri(uri)
        audioLabel.text = getString(R.string.audio_format, filename)
        statusLabel.text = "Muzyka w tle: $filename"

        // Jeśli wideo jest odtwarzane, uruchom audio
        if (player.isPlaying) {
            ap.play()
        }
    }

    private fun getFilenameFromUri(uri: Uri): String {
        // Spróbuj pobrać nazwę pliku z content resolvera
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        return cursor.getString(nameIndex)
                    }
                }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment ?: "audio"
    }

    // =========================================================================
    // Playback Controls
    // =========================================================================

    private val playHandler = Handler(Looper.getMainLooper())

    private fun togglePlayPause() {
        if (mediaFiles.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_files), Toast.LENGTH_SHORT).show()
            return
        }

        if (player.isPlaying) {
            player.pause()
            audioPlayer?.pause()
            playHandler.removeCallbacksAndMessages(null)
            btnPlayPause.text = getString(R.string.resume)
            statusLabel.text = getString(R.string.paused)
            // Pokaż pasek po pauzie
            showControls()
            hideHandler.removeCallbacks(hideRunnable)
        } else {
            // Ukryj pasek od razu
            controlsPanel.visibility = View.GONE
            controlsVisible = false

            // Odtwarzaj po 5 sekundach
            statusLabel.text = "Start za 5s…"
            playHandler.removeCallbacksAndMessages(null)
            playHandler.postDelayed({
                player.play()
                audioPlayer?.let {
                    if (backgroundAudioUri != null) it.play()
                }
                btnPlayPause.text = getString(R.string.pause)
                statusLabel.text = if (isMuted) getString(R.string.playing_muted)
                                    else getString(R.string.playing)
            }, 5000)
        }
    }

    private fun stopPlayback() {
        player.stop()
        player.seekTo(0, 0)
        audioPlayer?.stop()
        audioPlayer?.seekTo(0)
        btnPlayPause.text = getString(R.string.play)
        statusLabel.text = getString(R.string.stopped)
        updateFileCounter()
    }

    // =========================================================================
    // Fullscreen
    // =========================================================================

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)

        if (isFullscreen) {
            // Ukryj system bars
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            btnFullscreen.text = getString(R.string.window_mode)
            // Pokaż pasek tymczasowo
            showControlsTemporarily()
        } else {
            // Pokaż system bars
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
            btnFullscreen.text = getString(R.string.fullscreen)
            // Pokaż kontrolki tymczasowo
            showControlsTemporarily()
        }
    }

    private fun showControls() {
        controlsPanel.visibility = View.VISIBLE
        controlsVisible = true
        hideHandler.removeCallbacks(hideRunnable)
    }

    private fun showControlsTemporarily() {
        controlsPanel.visibility = View.VISIBLE
        controlsVisible = true
        scheduleHideControls()
    }

    private fun hideControls() {
        controlsPanel.visibility = View.GONE
        controlsVisible = false
        hideHandler.removeCallbacks(hideRunnable)
    }

    private fun scheduleHideControls() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, HIDE_CONTROLS_DELAY)
    }

    // =========================================================================
    // Loop & Random
    // =========================================================================

    private fun toggleLoop() {
        isLooping = !isLooping
        player.repeatMode = if (isLooping) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        btnLoop.text = getString(if (isLooping) R.string.loop_on else R.string.loop_off)
        updateInfoLabel()
    }

    private fun toggleRandom() {
        isRandomMode = !isRandomMode
        btnRandom.text = getString(if (isRandomMode) R.string.random_on else R.string.random_off)
        updateInfoLabel()

        // Przeładuj pliki w nowej kolejności
        if (mediaFiles.isNotEmpty()) {
            val wasPlaying = player.isPlaying
            val items = mediaFiles.zip(mediaNames).toMutableList()

            if (isRandomMode) {
                items.shuffle()
            } else {
                // Przywróć oryginalną kolejność — posortuj ponownie
                items.sortWith(
                    if (sortMode == SORT_LOCALE) {
                        val collator = Collator.getInstance()
                        compareBy(collator) { it.second }
                    } else {
                        compareBy { naturalSortKey(it.second) }
                    }
                )
            }

            mediaFiles.clear()
            mediaNames.clear()
            val mediaItems = mutableListOf<MediaItem>()
            for ((uri, name) in items) {
                mediaFiles.add(uri)
                mediaNames.add(name)
                mediaItems.add(MediaItem.fromUri(uri))
            }

            prefetchedIndices.clear()
            player.stop()
            player.clearMediaItems()
            player.setMediaItems(mediaItems)
            player.repeatMode = if (isLooping) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
            player.prepare()
            prefetchAhead(-1)

            updateFileCounter()

            if (wasPlaying) {
                player.play()
            }
        }
    }

    // =========================================================================
    // Go To
    // =========================================================================

    private fun showGotoDialog() {
        if (mediaFiles.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_files), Toast.LENGTH_SHORT).show()
            return
        }

        val total = mediaFiles.size
        val current = player.currentMediaItemIndex + 1

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            textSize = 24f
            setPadding(48, 32, 48, 32)
            selectAll()
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.goto_dialog_title))
            .setMessage(getString(R.string.goto_dialog_message, total))
            .setView(input)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val number = input.text.toString().toIntOrNull()
                if (number != null && number in 1..total) {
                    val targetIndex = number - 1
                    player.seekTo(targetIndex, 0)
                    updateFileCounter()
                    prefetchAhead(targetIndex)
                    if (!player.isPlaying) {
                        player.play()
                    }
                } else {
                    Toast.makeText(
                        this,
                        "Podaj liczbę od 1 do $total",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // =========================================================================
    // UI Updates
    // =========================================================================

    private fun updateInfoLabel() {
        val loopStr = if (isLooping) "ON" else "OFF"
        val randomStr = if (isRandomMode) "ON" else "OFF"
        infoLabel.text = getString(R.string.files_info_format, mediaFiles.size, loopStr, randomStr)
    }

    private fun updateFileCounter() {
        val total = mediaFiles.size
        val current = if (total > 0) player.currentMediaItemIndex + 1 else 0
        currentFileLabel.text = getString(R.string.file_counter_format, current, total)
    }

    // =========================================================================
    // Back button
    // =========================================================================

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isFullscreen) {
            toggleFullscreen()
        } else {
            super.onBackPressed()
        }
    }
}
