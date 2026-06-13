package com.example

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

// ==========================================
// 1. DATA LAYER (ROOM PERSISTENCE)
// ==========================================

@Entity(tableName = "scores")
data class GameScore(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val score: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val themeUsed: String,
    val wallModeUsed: String,
    val speedUsed: String
)

@Dao
interface ScoreDao {
    @Query("SELECT * FROM scores ORDER BY score DESC, timestamp DESC LIMIT 5")
    fun getTopScores(): Flow<List<GameScore>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScore(score: GameScore)

    @Query("DELETE FROM scores")
    suspend fun clearScores()
}

@Database(entities = [GameScore::class], version = 1, exportSchema = false)
abstract class SnakeDatabase : RoomDatabase() {
    abstract fun scoreDao(): ScoreDao

    companion object {
        @Volatile
        private var INSTANCE: SnakeDatabase? = null

        fun getDatabase(context: Context): SnakeDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SnakeDatabase::class.java,
                    "snake_db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class ScoreRepository(private val scoreDao: ScoreDao) {
    val topScores: Flow<List<GameScore>> = scoreDao.getTopScores()

    suspend fun saveScore(score: GameScore) {
        scoreDao.insertScore(score)
    }

    suspend fun clear() {
        scoreDao.clearScores()
    }
}

// ==========================================
// 2. GAME ENGINE MODELS
// ==========================================

enum class GameStatus { IDLE, PLAYING, PAUSED, GAME_OVER }
enum class Direction { UP, DOWN, LEFT, RIGHT }

enum class GameSpeed(val label: String, val delayMs: Long) {
    EASY("Slow", 240L),
    MEDIUM("Normal", 150L),
    HARD("Fast", 95L)
}

enum class WallMode(val label: String) {
    SOLID("Hard Wall"),
    WRAP("Wrap Grid")
}

data class Point(val x: Int, val y: Int)

enum class ThemePreset(
    val label: String,
    val snakeHeadColor: Color,
    val snakeBodyColor: Color,
    val appleColor: Color,
    val boardBackground: Color,
    val gridColor: Color,
    val glowColor: Color
) {
    SOPHISTICATED_DARK(
        "Sophisticated Dark",
        Color(0xFFE2F6D1), // Pale Green Head
        Color(0xFFB4E197), // Soft Green Body
        Color(0xFFFFB4AB), // Coral soft apple
        Color(0xFF000000), // Solid Black Grid Background
        Color(0x2249454F), // Sophisticated grid line / dots
        Color(0x33FFB4AB)  // Glow
    ),
    CYBER_SYNTH(
        "Cyber Synth",
        Color(0xFFFF007F), // Neon Pink
        Color(0xFFFF529D), // Light Pink Body
        Color(0xFF00F0FF), // Neon Cyan Apple
        Color(0xFF0C031A), // Deep Night Purple Board
        Color(0xFF26123A), // Low Grid
        Color(0x3300F0FF)  // Glow
    ),
    CLASSIC_ARCADE(
        "Arcade Green",
        Color(0xFF056B09), // Forest Green
        Color(0xFF2EA133), // Grass Green
        Color(0xFFD61818), // Juicy Red Apple
        Color(0xFF141714), // Dark Olive Slate
        Color(0xFF1D261D), // Dark Olive Lines
        Color(0x33D61818)
    ),
    SOLAR_EMBER(
        "Sunset Glow",
        Color(0xFFE65100), // Rich Amber
        Color(0xFFFFB300), // Sun Gold Body
        Color(0xFFFFFF00), // Lemon Glowing Apple
        Color(0xFF1C130D), // Earth Charcoal Board
        Color(0xFF332015), // Rust Grid Lines
        Color(0x33FFFF00)
    ),
    MATRIX(
        "Term Grid",
        Color(0xFF00FF00), // Toxic Green Head
        Color(0xFF00AA00), // Medium Green Body
        Color(0xFF00E5FF), // Teal Core
        Color(0xFF020902), // Abyss Black
        Color(0xFF082208), // Terminal Lines
        Color(0x3300E5FF)
    )
}

// Sound click synthesis using standard Android ToneGenerator
class RetroSpeaker(context: Context) {
    private var toneGen: ToneGenerator? = null
    var isEnabled = true

    init {
        try {
            toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 65)
        } catch (e: Exception) {
            toneGen = null
        }
    }

    fun playEat() {
        if (!isEnabled) return
        try {
            toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
        } catch (e: Exception) { /* safe ignore */ }
    }

    fun playCrash() {
        if (!isEnabled) return
        try {
            toneGen?.startTone(ToneGenerator.TONE_SUP_ERROR, 250)
        } catch (e: Exception) { /* safe ignore */ }
    }

    fun playClick() {
        if (!isEnabled) return
        try {
            toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 40)
        } catch (e: Exception) { /* safe ignore */ }
    }

    fun playMove() {
        if (!isEnabled) return
        try {
            toneGen?.startTone(ToneGenerator.TONE_SUP_PIP, 15)
        } catch (e: Exception) { /* safe ignore */ }
    }
}

// ==========================================
// 3. STATE REPRESENTATION
// ==========================================

data class SnakeGameState(
    val snake: List<Point> = listOf(Point(10, 10), Point(10, 11), Point(10, 12)),
    val direction: Direction = Direction.UP,
    val apple: Point = Point(10, 5),
    val score: Int = 0,
    val highLocalScore: Int = 0,
    val status: GameStatus = GameStatus.IDLE,
    val speed: GameSpeed = GameSpeed.MEDIUM,
    val wallMode: WallMode = WallMode.SOLID,
    val currentTheme: ThemePreset = ThemePreset.SOPHISTICATED_DARK,
    val isMuted: Boolean = false
)

// ==========================================
// 4. VIEW MODEL
// ==========================================

class SnakeViewModel(private val repository: ScoreRepository) : ViewModel() {

    private val _gameState = MutableStateFlow(SnakeGameState())
    val gameState: StateFlow<SnakeGameState> = _gameState.asStateFlow()

    // Top 5 scores reactively loaded from Room
    val leaderboardScores: StateFlow<List<GameScore>> = repository.topScores
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val gridWidth = 20
    private val gridHeight = 20
    
    // To block immediate turn-backs
    private var lastMovementDirection = Direction.UP

    fun changeDirection(newDir: Direction) {
        val currState = _gameState.value
        if (currState.status != GameStatus.PLAYING) return

        // Block moves directly opposite to the last processed motion direction
        val isOpposite = when (newDir) {
            Direction.UP -> lastMovementDirection == Direction.DOWN
            Direction.DOWN -> lastMovementDirection == Direction.UP
            Direction.LEFT -> lastMovementDirection == Direction.RIGHT
            Direction.RIGHT -> lastMovementDirection == Direction.LEFT
        }

        if (!isOpposite) {
            _gameState.update { it.copy(direction = newDir) }
        }
    }

    fun togglePauseResume() {
        _gameState.update {
            val nextStatus = when (it.status) {
                GameStatus.PLAYING -> GameStatus.PAUSED
                GameStatus.PAUSED -> GameStatus.PLAYING
                else -> it.status
            }
            it.copy(status = nextStatus)
        }
    }

    fun toggleMute() {
        _gameState.update { it.copy(isMuted = !it.isMuted) }
    }

    fun setSpeed(newSpeed: GameSpeed) {
        _gameState.update { it.copy(speed = newSpeed) }
    }

    fun setWallMode(mode: WallMode) {
        _gameState.update { it.copy(wallMode = mode) }
    }

    fun setTheme(theme: ThemePreset) {
        _gameState.update { it.copy(currentTheme = theme) }
    }

    fun startGame() {
        val resetSnake = listOf(Point(10, 10), Point(10, 11), Point(10, 12))
        val firstApple = generateRandomApple(resetSnake)
        
        lastMovementDirection = Direction.UP
        
        _gameState.update {
            it.copy(
                snake = resetSnake,
                direction = Direction.UP,
                apple = firstApple,
                score = 0,
                status = GameStatus.PLAYING
            )
        }
    }

    fun tick(speaker: RetroSpeaker) {
        val state = _gameState.value
        if (state.status != GameStatus.PLAYING) return

        if (SnakeEngine.isNativeActive()) {
            val snakeX = state.snake.map { it.x }.toIntArray()
            val snakeY = state.snake.map { it.y }.toIntArray()
            val dirInt = when (state.direction) {
                Direction.UP -> 0
                Direction.DOWN -> 1
                Direction.LEFT -> 2
                Direction.RIGHT -> 3
            }
            val modeInt = when (state.wallMode) {
                WallMode.SOLID -> 0
                WallMode.WRAP -> 1
            }

            try {
                val result = SnakeEngine.stepGame(
                    snakeX,
                    snakeY,
                    dirInt,
                    state.apple.x,
                    state.apple.y,
                    modeInt,
                    gridWidth,
                    gridHeight
                )

                val isGameOver = result[0] == 1
                val appleEaten = result[1] == 1
                val newAppleX = result[2]
                val newAppleY = result[3]

                lastMovementDirection = state.direction

                if (isGameOver) {
                    gameOverTriggered(speaker)
                    return
                }

                val newSnake = ArrayList<Point>()
                val numCoords = result.size - 4
                for (i in 0 until numCoords step 2) {
                    newSnake.add(Point(result[4 + i], result[4 + i + 1]))
                }

                if (appleEaten) {
                    val newScore = state.score + 10
                    val updatedHighScore = if (newScore > state.highLocalScore) newScore else state.highLocalScore
                    speaker.playEat()
                    _gameState.update {
                        it.copy(
                            snake = newSnake,
                            apple = Point(newAppleX, newAppleY),
                            score = newScore,
                            highLocalScore = updatedHighScore
                        )
                    }
                } else {
                    _gameState.update {
                        it.copy(snake = newSnake)
                    }
                    speaker.playMove()
                }
                return
            } catch (e: Exception) {
                android.util.Log.e("SnakeViewModel", "C++ Engine call failed: ${e.message}")
            }
        }

        val head = state.snake.first()
        var nextX = head.x
        var nextY = head.y

        when (state.direction) {
            Direction.UP -> nextY -= 1
            Direction.DOWN -> nextY += 1
            Direction.LEFT -> nextX -= 1
            Direction.RIGHT -> nextX += 1
        }

        // 1. Boundary / Wall Collision
        if (state.wallMode == WallMode.SOLID) {
            if (nextX < 0 || nextX >= gridWidth || nextY < 0 || nextY >= gridHeight) {
                gameOverTriggered(speaker)
                return
            }
        } else {
            // WRAP Mode
            nextX = (nextX + gridWidth) % gridWidth
            nextY = (nextY + gridHeight) % gridHeight
        }

        val nextPoint = Point(nextX, nextY)

        // 2. Self Collision Check: ends if snake touches itself
        if (state.snake.contains(nextPoint)) {
            gameOverTriggered(speaker)
            return
        }

        // Record last processed direction
        lastMovementDirection = state.direction

        // 3. Apple Eating Collision
        val newSnake = ArrayList<Point>()
        newSnake.add(nextPoint) // New head

        if (nextPoint.x == state.apple.x && nextPoint.y == state.apple.y) {
            // Ate Apple! Grow the snake
            newSnake.addAll(state.snake)
            val newScore = state.score + 10
            val nextApple = generateRandomApple(newSnake)
            
            speaker.playEat()
            
            val updatedHighScore = if (newScore > state.highLocalScore) newScore else state.highLocalScore

            _gameState.update {
                it.copy(
                    snake = newSnake,
                    apple = nextApple,
                    score = newScore,
                    highLocalScore = updatedHighScore
                )
            }
        } else {
            // Simple Move forward
            newSnake.addAll(state.snake.subList(0, state.snake.size - 1))
            _gameState.update {
                it.copy(snake = newSnake)
            }
            speaker.playMove()
        }
    }

    private fun generateRandomApple(snake: List<Point>): Point {
        var attempts = 0
        while (attempts < 100) {
            val x = Random.nextInt(0, gridWidth)
            val y = Random.nextInt(0, gridHeight)
            val point = Point(x, y)
            if (!snake.contains(point)) {
                return point
            }
            attempts++
        }
        return Point(5, 5) // fallback
    }

    private fun gameOverTriggered(speaker: RetroSpeaker) {
        speaker.playCrash()
        _gameState.update { it.copy(status = GameStatus.GAME_OVER) }

        // Save current score and metrics to Room Leaderboard
        val currentS = _gameState.value
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveScore(
                GameScore(
                    score = currentS.score,
                    themeUsed = currentS.currentTheme.label,
                    wallModeUsed = currentS.wallMode.label,
                    speedUsed = currentS.speed.label
                )
            )
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clear()
        }
    }
}

class SnakeViewModelFactory(private val repository: ScoreRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SnakeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SnakeViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

// ==========================================
// 5. ACTIVITY ENTRY POINT & UI LAYOUT
// ==========================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        val database = SnakeDatabase.getDatabase(this)
        val repository = ScoreRepository(database.scoreDao())

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("main_scaffold"),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        SnakeGameApp(repository)
                    }
                }
            }
        }
    }
}

@Composable
fun SnakeGameApp(repository: ScoreRepository) {
    val context = LocalContext.current
    
    // Lazy view model creation using the custom Factory
    val viewModel: SnakeViewModel = viewModel(
        factory = SnakeViewModelFactory(repository)
    )

    val state by viewModel.gameState.collectAsState()
    val speaker = remember { RetroSpeaker(context) }
    
    // Sync speaker mutable state
    speaker.isEnabled = !state.isMuted

    // Score eating floating animation scope
    var prevScore by remember { mutableStateOf(0) }
    var triggerFloatScore by remember { mutableStateOf(false) }

    // Screen shake trigger to highlight collision in canvas
    val boardShakeAnim = remember { Animatable(0f) }

    // Trigger score pop animation and brief screen shake on score rise
    LaunchedEffect(state.score) {
        if (state.score > prevScore) {
            triggerFloatScore = true
            delay(850)
            triggerFloatScore = false
            
            // Screen shake relative to score change
            boardShakeAnim.snapTo(4.5f)
            boardShakeAnim.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 85f))
        }
        prevScore = state.score
    }

    // Huge screen shake on GAME OVER
    LaunchedEffect(state.status) {
        if (state.status == GameStatus.GAME_OVER) {
            boardShakeAnim.snapTo(14f)
            boardShakeAnim.animateTo(0f, spring(dampingRatio = 0.25f, stiffness = 60f))
        }
    }

    // Core Game Tick Timer Loop block
    LaunchedEffect(state.status, state.speed) {
        if (state.status == GameStatus.PLAYING) {
            while (isActive) {
                delay(state.speed.delayMs)
                viewModel.tick(speaker)
            }
        }
    }

    val scrollState = rememberScrollState()

    // Adaptive UI Setup (Landscape vs Vertical responsive layout)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1C1B1F),
                        Color(0xFF131215)
                    )
                )
            )
            .padding(14.dp)
    ) {
        val isWide = maxWidth > 600.dp

        if (isWide) {
            // Adaptive Landscape layout: Wide tablets or devices
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Game Board Canvas (Left side)
                Box(
                    modifier = Modifier
                        .weight(1.1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    GameBoardContainer(
                        state = state,
                        boardShake = boardShakeAnim.value,
                        triggerFloat = triggerFloatScore,
                        viewModel = viewModel,
                        speaker = speaker
                    )
                }

                // Control panel and Leaderboard info (Right side)
                Column(
                    modifier = Modifier
                        .weight(0.9f)
                        .fillMaxHeight()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ScoreTerminalHeader(state = state, viewModel = viewModel, speaker = speaker)
                    InteractiveRetroControls(state = state, viewModel = viewModel, speaker = speaker)
                    SettingsPanelSection(state = state, viewModel = viewModel, speaker = speaker)
                    LeaderboardModuleBoard(viewModel = viewModel)
                }
            }
        } else {
            // Smartphone Portrait layout
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ScoreTerminalHeader(state = state, viewModel = viewModel, speaker = speaker)
                
                GameBoardContainer(
                    state = state,
                    boardShake = boardShakeAnim.value,
                    triggerFloat = triggerFloatScore,
                    viewModel = viewModel,
                    speaker = speaker
                )

                InteractiveRetroControls(state = state, viewModel = viewModel, speaker = speaker)
                
                SettingsPanelSection(state = state, viewModel = viewModel, speaker = speaker)
                
                LeaderboardModuleBoard(viewModel = viewModel)
                
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

// ==========================================
// 6. UI COMPREHENSIVE SUB-CELLS
// ==========================================

@Composable
fun ScoreTerminalHeader(
    state: SnakeGameState,
    viewModel: SnakeViewModel,
    speaker: RetroSpeaker
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930)),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFF49454F), RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Arcade LCD Display scores
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column {
                    Text(
                        text = "SCORE",
                        fontSize = 11.sp,
                        color = Color(0xFFD0BCFF),
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = String.format(Locale.getDefault(), "%04d", state.score),
                        fontSize = 24.sp,
                        color = state.currentTheme.snakeBodyColor, // Lavender / Sage green depending on custom presets, default is Sophisticated Green
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.testTag("arcade_score")
                    )
                }

                Column {
                    Text(
                        text = "BEST LOCAL",
                        fontSize = 11.sp,
                        color = Color(0xFFD0BCFF),
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = String.format(Locale.getDefault(), "%04d", state.highLocalScore),
                        fontSize = 24.sp,
                        color = Color(0xFFB4E197),
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Quick Actions (Mute & Status Controls)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = {
                        speaker.playClick()
                        viewModel.toggleMute()
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFF1C1B1F), CircleShape)
                        .border(1.dp, Color(0xFF49454F), CircleShape)
                        .testTag("mute_toggle_button")
                ) {
                    Icon(
                        imageVector = if (state.isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = "Mute or Unmute Audio",
                        tint = Color(0xFFD0BCFF),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Play / Pause / Restart indicator
                if (state.status == GameStatus.PLAYING || state.status == GameStatus.PAUSED) {
                    Button(
                        onClick = {
                            speaker.playClick()
                            viewModel.togglePauseResume()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.status == GameStatus.PLAYING) Color(0xFF49454F) else Color(0xFFD0BCFF),
                            contentColor = if (state.status == GameStatus.PLAYING) Color(0xFFE6E1E5) else Color(0xFF381E72)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier
                            .height(40.dp)
                            .border(1.dp, Color(0xFF49454F), RoundedCornerShape(10.dp))
                            .testTag("play_pause_button")
                    ) {
                        Icon(
                            imageVector = if (state.status == GameStatus.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play Pause state toggler",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (state.status == GameStatus.PLAYING) "PAUSE" else "RESUME",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            speaker.playClick()
                            viewModel.startGame()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFD0BCFF),
                            contentColor = Color(0xFF381E72)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        modifier = Modifier
                            .height(40.dp)
                            .testTag("start_game_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Start game button",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (state.status == GameStatus.GAME_OVER) "REPLAY" else "START",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GameBoardContainer(
    state: SnakeGameState,
    boardShake: Float,
    triggerFloat: Boolean,
    viewModel: SnakeViewModel,
    speaker: RetroSpeaker
) {
    val hapticFeedback = LocalHapticFeedback.current

    // Drag-swipe logic helper coordinates
    var cumulativeDrag by remember { mutableStateOf(Offset.Zero) }

    // Floating food eat alert pulse
    val applePulseTransition = rememberInfiniteTransition(label = "pulse")
    val appleSizeCoeff by applePulseTransition.animateFloat(
        initialValue = 0.72f,
        targetValue = 0.96f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_anim"
    )

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = state.currentTheme.boardBackground),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f) // Square grid constraints
            .padding(vertical = 4.dp)
            .shadow(10.dp, RoundedCornerShape(16.dp))
            .border(4.dp, Color(0xFF49454F), RoundedCornerShape(16.dp))
            // Apply visual screen shake offsets on the board
            .offset(
                x = if (boardShake == 0f) 0.dp else (Random.nextFloat() * boardShake - boardShake / 2).dp,
                y = if (boardShake == 0f) 0.dp else (Random.nextFloat() * boardShake - boardShake / 2).dp
            )
            // Gesture Swipe controls
            .pointerInput(state.status) {
                if (state.status != GameStatus.PLAYING) return@pointerInput
                detectDragGestures(
                    onDragStart = { cumulativeDrag = Offset.Zero },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        cumulativeDrag += dragAmount
                    },
                    onDragEnd = {
                        val xOffset = cumulativeDrag.x
                        val yOffset = cumulativeDrag.y
                        if (abs(xOffset) > 40f || abs(yOffset) > 40f) {
                            if (abs(xOffset) > abs(yOffset)) {
                                if (xOffset > 0) {
                                    viewModel.changeDirection(Direction.RIGHT)
                                } else {
                                    viewModel.changeDirection(Direction.LEFT)
                                }
                            } else {
                                if (yOffset > 0) {
                                    viewModel.changeDirection(Direction.DOWN)
                                } else {
                                    viewModel.changeDirection(Direction.UP)
                                }
                            }
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                )
            }
            .testTag("game_board_card")
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            
            // 1. Core Canvas drawing engine
            Canvas(modifier = Modifier.fillMaxSize()) {
                val gridX = 20
                val gridY = 20
                val cellW = size.width / gridX
                val cellH = size.height / gridY

                // Drew retro grid boundaries
                val gridStroke = 0.6.dp.toPx()
                for (i in 1 until gridX) {
                    val lineX = i * cellW
                    drawLine(
                        color = state.currentTheme.gridColor,
                        start = Offset(lineX, 0f),
                        end = Offset(lineX, size.height),
                        strokeWidth = gridStroke
                    )
                }
                for (j in 1 until gridY) {
                    val lineY = j * cellH
                    drawLine(
                        color = state.currentTheme.gridColor,
                        start = Offset(0f, lineY),
                        end = Offset(size.width, lineY),
                        strokeWidth = gridStroke
                    )
                }

                // Apple/Fruit representation painting
                val appleX = state.apple.x * cellW
                val appleY = state.apple.y * cellH
                val appleRadius = (cellW / 2) * appleSizeCoeff
                
                // Outer glow shadow
                drawCircle(
                    color = state.currentTheme.glowColor,
                    radius = appleRadius * 1.5f,
                    center = Offset(appleX + cellW / 2, appleY + cellH / 2)
                )
                // Red/Cyan glowing body core
                drawCircle(
                    color = state.currentTheme.appleColor,
                    radius = appleRadius,
                    center = Offset(appleX + cellW / 2, appleY + cellH / 2)
                )
                // Stem branch
                drawLine(
                    color = Color(0xFF4CAF50),
                    start = Offset(appleX + cellW / 2, appleY + cellH * 0.35f),
                    end = Offset(appleX + cellW * 0.65f, appleY + cellH * 0.15f),
                    strokeWidth = 2.dp.toPx()
                )

                // Paint Snake body segment list
                val padPixel = 1.3.dp.toPx()
                state.snake.forEachIndexed { index, segment ->
                    val segX = segment.x * cellW
                    val segY = segment.y * cellH
                    
                    val isFirstHead = index == 0
                    if (isFirstHead) {
                        // Drawing Head segment (distinct color, rounded, glowing eye graphics)
                        drawRoundRect(
                            color = state.currentTheme.snakeHeadColor,
                            topLeft = Offset(segX + padPixel, segY + padPixel),
                            size = Size(cellW - padPixel * 2, cellH - padPixel * 2),
                            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                        )

                        // Cute expressive eyes looking in movement directions
                        val eyeD = cellW * 0.16f
                        val pupilD = cellW * 0.09f
                        val eyeOffset = cellW * 0.31f
                        val headC = Offset(segX + cellW / 2, segY + cellH / 2)

                        when (state.direction) {
                            Direction.UP -> {
                                // Left eye
                                drawCircle(Color.White, radius = eyeD, center = Offset(headC.x - eyeOffset, headC.y - eyeOffset))
                                drawCircle(Color.Black, radius = pupilD, center = Offset(headC.x - eyeOffset, headC.y - eyeOffset - 1f))
                                // Right eye
                                drawCircle(Color.White, radius = eyeD, center = Offset(headC.x + eyeOffset, headC.y - eyeOffset))
                                drawCircle(Color.Black, radius = pupilD, center = Offset(headC.x + eyeOffset, headC.y - eyeOffset - 1f))
                            }
                            Direction.DOWN -> {
                                drawCircle(Color.White, radius = eyeD, center = Offset(headC.x - eyeOffset, headC.y + eyeOffset))
                                drawCircle(Color.Black, radius = pupilD, center = Offset(headC.x - eyeOffset, headC.y + eyeOffset + 1f))
                                drawCircle(Color.White, radius = eyeD, center = Offset(headC.x + eyeOffset, headC.y + eyeOffset))
                                drawCircle(Color.Black, radius = pupilD, center = Offset(headC.x + eyeOffset, headC.y + eyeOffset + 1f))
                            }
                            Direction.LEFT -> {
                                drawCircle(Color.White, radius = eyeD, center = Offset(headC.x - eyeOffset, headC.y - eyeOffset))
                                drawCircle(Color.Black, radius = pupilD, center = Offset(headC.x - eyeOffset - 1f, headC.y - eyeOffset))
                                drawCircle(Color.White, radius = eyeD, center = Offset(headC.x - eyeOffset, headC.y + eyeOffset))
                                drawCircle(Color.Black, radius = pupilD, center = Offset(headC.x - eyeOffset - 1f, headC.y + eyeOffset))
                            }
                            Direction.RIGHT -> {
                                drawCircle(Color.White, radius = eyeD, center = Offset(headC.x + eyeOffset, headC.y - eyeOffset))
                                drawCircle(Color.Black, radius = pupilD, center = Offset(headC.x + eyeOffset + 1f, headC.y - eyeOffset))
                                drawCircle(Color.White, radius = eyeD, center = Offset(headC.x + eyeOffset, headC.y + eyeOffset))
                                drawCircle(Color.Black, radius = pupilD, center = Offset(headC.x + eyeOffset + 1f, headC.y + eyeOffset))
                            }
                        }
                    } else {
                        // Regular Body segments
                        drawRoundRect(
                            color = state.currentTheme.snakeBodyColor,
                            topLeft = Offset(segX + padPixel * 1.5f, segY + padPixel * 1.5f),
                            size = Size(cellW - padPixel * 3, cellH - padPixel * 3),
                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                        )
                    }
                }
            }

            // 2. Overlay Screen Displays (Game Over, Idle, Paused)
            androidx.compose.animation.AnimatedVisibility(
                visible = state.status != GameStatus.PLAYING,
                enter = fadeIn(animationSpec = tween(250)),
                exit = fadeOut(animationSpec = tween(200)),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xD91C1B1F))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        when (state.status) {
                            GameStatus.IDLE -> {
                                Icon(
                                    imageVector = Icons.Default.VideogameAsset,
                                    contentDescription = "Retro Console logo",
                                    tint = Color(0xFFD0BCFF),
                                    modifier = Modifier.size(72.dp)
                                )
                                Text(
                                    text = "SNAKE ARCADE",
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFFE6E1E5),
                                    textAlign = TextAlign.Center,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.testTag("game_title_text")
                                )
                                Text(
                                    text = "Ready to test your reflexes? Control the glowing snake, eat apples to grow, and avoid self-collision!",
                                    fontSize = 13.sp,
                                    color = Color(0xFFCCC2DC),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 14.dp)
                                )
                                Button(
                                    onClick = {
                                        speaker.playClick()
                                        viewModel.startGame()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFD0BCFF),
                                        contentColor = Color(0xFF381E72)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.testTag("arcade_start_btn")
                                ) {
                                    Text("PLAY NOW", fontWeight = FontWeight.Bold)
                                }
                            }
                            GameStatus.PAUSED -> {
                                Icon(
                                    imageVector = Icons.Default.PauseCircle,
                                    contentDescription = "Paused",
                                    tint = Color(0xFFD0BCFF),
                                    modifier = Modifier.size(64.dp)
                                )
                                Text(
                                    text = "GAME PAUSED",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE6E1E5),
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Score remains frozen at: ${state.score} points",
                                    fontSize = 13.sp,
                                    color = Color(0xFFCCC2DC)
                                )
                                Button(
                                    onClick = {
                                        speaker.playClick()
                                        viewModel.togglePauseResume()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFB4E197),
                                        contentColor = Color(0xFF1C1B1F)
                                    ),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("RESUME", fontWeight = FontWeight.Bold)
                                }
                            }
                            GameStatus.GAME_OVER -> {
                                Icon(
                                    imageVector = Icons.Default.Dangerous,
                                    contentDescription = "Dead state",
                                    tint = Color(0xFFFFB4AB),
                                    modifier = Modifier.size(64.dp)
                                )
                                Text(
                                    text = "GAME OVER",
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFFFFB4AB),
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.testTag("game_over_title")
                                )
                                Text(
                                    text = "Your Snake crashed! Self-collision or boundary strike registered.",
                                    fontSize = 12.sp,
                                    color = Color(0xFFE6E1E5),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                                
                                Card(
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930)),
                                    modifier = Modifier
                                        .padding(vertical = 4.dp)
                                        .border(1.dp, Color(0xFF49454F), RoundedCornerShape(10.dp))
                                ) {
                                    Text(
                                        text = "FINAL SCORE: ${state.score} PTS",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFFD0BCFF),
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }

                                Button(
                                    onClick = {
                                        speaker.playClick()
                                        viewModel.startGame()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFD0BCFF),
                                        contentColor = Color(0xFF381E72)
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.testTag("restart_button")
                                ) {
                                    Text("TRY AGAIN", fontWeight = FontWeight.Bold)
                                }
                            }
                            GameStatus.PLAYING -> {
                                // No-op: handled in ambient gameplay rendering
                            }
                        }
                    }
                }
            }

            // 3. Floating Apple "+10" Eat Indicator
            if (triggerFloat && state.status == GameStatus.PLAYING) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    FloatingScoreText()
                }
            }
        }
    }
}

@Composable
fun FloatingScoreText() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(280, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = ""
    )
    
    Text(
        text = "+10 PTS",
        color = Color(0xFF00FFCC),
        fontSize = 28.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.ExtraBold,
        modifier = Modifier
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .shadow(4.dp)
    )
}

// ==========================================
// 7. RETRO CONTROLLER TOUCH D-PAD
// ==========================================

@Composable
fun InteractiveRetroControls(
    state: SnakeGameState,
    viewModel: SnakeViewModel,
    speaker: RetroSpeaker
) {
    val hapticFeedback = LocalHapticFeedback.current

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930)),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp)
            .border(1.dp, Color(0xFF49454F), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "RETRO TACTILE D-PAD",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFD0BCFF),
                fontFamily = FontFamily.Monospace
            )

            // Round Concentric D-pad layout block
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .background(Color(0xFF1C1B1F), CircleShape)
                    .border(3.dp, Color(0xFF49454F), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // Direction UP
                IconButton(
                    onClick = {
                        speaker.playMove()
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.changeDirection(Direction.UP)
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 4.dp)
                        .size(45.dp)
                        .background(if (state.direction == Direction.UP) Color(0xFFD0BCFF) else Color(0xFF49454F), CircleShape)
                        .border(1.dp, Color(0xFF49454F), CircleShape)
                        .testTag("dpad_up")
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Arrow Up Direction Button",
                        tint = if (state.direction == Direction.UP) Color(0xFF381E72) else Color(0xFFE6E1E5),
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Direction LEFT
                IconButton(
                    onClick = {
                        speaker.playMove()
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.changeDirection(Direction.LEFT)
                    },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 4.dp)
                        .size(45.dp)
                        .background(if (state.direction == Direction.LEFT) Color(0xFFD0BCFF) else Color(0xFF49454F), CircleShape)
                        .border(1.dp, Color(0xFF49454F), CircleShape)
                        .testTag("dpad_left")
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowLeft,
                        contentDescription = "Arrow Left Direction Button",
                        tint = if (state.direction == Direction.LEFT) Color(0xFF381E72) else Color(0xFFE6E1E5),
                        modifier = Modifier.size(28.dp)
                    )
                }

                // D-pad core Center circle button
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(Color(0xFF2B2930), CircleShape)
                        .border(2.dp, Color(0xFF49454F), CircleShape)
                        .clickable {
                            speaker.playClick()
                            if (state.status == GameStatus.PLAYING || state.status == GameStatus.PAUSED) {
                                viewModel.togglePauseResume()
                            } else {
                                viewModel.startGame()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (state.status == GameStatus.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "D-pad central run indicator",
                        tint = Color(0xFFD0BCFF),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Direction RIGHT
                IconButton(
                    onClick = {
                        speaker.playMove()
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.changeDirection(Direction.RIGHT)
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp)
                        .size(45.dp)
                        .background(if (state.direction == Direction.RIGHT) Color(0xFFD0BCFF) else Color(0xFF49454F), CircleShape)
                        .border(1.dp, Color(0xFF49454F), CircleShape)
                        .testTag("dpad_right")
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = "Arrow Right Direction Button",
                        tint = if (state.direction == Direction.RIGHT) Color(0xFF381E72) else Color(0xFFE6E1E5),
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Direction DOWN
                IconButton(
                    onClick = {
                        speaker.playMove()
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.changeDirection(Direction.DOWN)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                        .size(45.dp)
                        .background(if (state.direction == Direction.DOWN) Color(0xFFD0BCFF) else Color(0xFF49454F), CircleShape)
                        .border(1.dp, Color(0xFF49454F), CircleShape)
                        .testTag("dpad_down")
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Arrow Down Direction Button",
                        tint = if (state.direction == Direction.DOWN) Color(0xFF381E72) else Color(0xFFE6E1E5),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Text(
                text = "SWIPE OR DRAG DIRECTLY ON CANVAS AS ALTERNATIVE CONTROLLER",
                fontSize = 8.5.sp,
                color = Color(0xFFCCC2DC),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

// ==========================================
// 8. CUSTOMIZATION SETTINGS BAR
// ==========================================

@Composable
fun SettingsPanelSection(
    state: SnakeGameState,
    viewModel: SnakeViewModel,
    speaker: RetroSpeaker
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930)),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp)
            .border(1.dp, Color(0xFF49454F), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "RETRO SYSTEM CONFIGURATION",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFD0BCFF),
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 2.dp)
            )

            // Active Game Engine Status Row
            val nativeActive = SnakeEngine.isNativeActive()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1C1B1F))
                    .border(1.dp, Color(0xFF49454F), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Native C++ Core Execution", fontSize = 11.sp, color = Color(0xFFCCC2DC))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(if (nativeActive) Color(0xFFB4E197) else Color(0xFFFFB4AB), CircleShape)
                    )
                    Text(
                        text = if (nativeActive) "ACTIVE (C++)" else "HYBRID READY",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (nativeActive) Color(0xFFB4E197) else Color(0xFFD0BCFF),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Speed Level Toggle Selector
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Select CPU Speed Level", fontSize = 11.sp, color = Color(0xFFCCC2DC))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GameSpeed.values().forEach { currentSpeed ->
                        val isSelected = state.speed == currentSpeed
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFFD0BCFF) else Color(0xFF1C1B1F))
                                .border(1.dp, Color(0xFF49454F), RoundedCornerShape(8.dp))
                                .clickable {
                                    speaker.playClick()
                                    viewModel.setSpeed(currentSpeed)
                                }
                                .testTag("speed_${currentSpeed.name.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = currentSpeed.label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color(0xFF381E72) else Color(0xFFE6E1E5)
                            )
                        }
                    }
                }
            }

            // Wall Collision Mode Selector
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Grid Boundary Physics", fontSize = 11.sp, color = Color(0xFFCCC2DC))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WallMode.values().forEach { mode ->
                        val isSelected = state.wallMode == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFFD0BCFF) else Color(0xFF1C1B1F))
                                .border(1.dp, Color(0xFF49454F), RoundedCornerShape(8.dp))
                                .clickable {
                                    speaker.playClick()
                                    viewModel.setWallMode(mode)
                                }
                                .testTag("wall_mode_${mode.name.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = mode.label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color(0xFF381E72) else Color(0xFFE6E1E5)
                            )
                        }
                    }
                }
            }

            // Palette Theme Color presets selector
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Arcade Screen Visual Palette", fontSize = 11.sp, color = Color(0xFFCCC2DC))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ThemePreset.values().forEach { currentPreset ->
                        val isSelected = state.currentTheme == currentPreset
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFF49454F) else Color(0xFF1C1B1F))
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) Color(0xFFD0BCFF) else Color(0xFF49454F),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    speaker.playClick()
                                    viewModel.setTheme(currentPreset)
                                }
                                .padding(vertical = 6.dp)
                                .testTag("theme_${currentPreset.name.lowercase()}"),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Theme representation colors dots
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(currentPreset.snakeHeadColor)
                                )
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(currentPreset.appleColor)
                                )
                            }
                            Text(
                                text = currentPreset.label,
                                fontSize = 8.sp,
                                color = if (isSelected) Color(0xFFD0BCFF) else Color(0xFFCCC2DC),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 9. ROOM DATABASE SCORES LEADERBOARD PANEL
// ==========================================

@Composable
fun LeaderboardModuleBoard(viewModel: SnakeViewModel) {
    val scoresList by viewModel.leaderboardScores.collectAsState()
    val context = LocalContext.current
    val speaker = remember { RetroSpeaker(context) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930)),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp)
            .border(1.dp, Color(0xFF49454F), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.EmojiEvents,
                        contentDescription = "Trophy logo",
                        tint = Color(0xFFD0BCFF),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "ROOM LOCAL LEADERBOARD",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFD0BCFF),
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Clear history button
                if (scoresList.isNotEmpty()) {
                    Text(
                        text = "CLEAR ALL",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFFFB4AB),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clickable {
                                speaker.playClick()
                                viewModel.clearHistory()
                            }
                            .padding(4.dp)
                            .testTag("clear_leaderboard_button")
                    )
                }
            }

            if (scoresList.isEmpty()) {
                // Empty database state
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No recorded runs yet.\nStart playing to build your high scores!",
                        fontSize = 11.sp,
                        color = Color(0xFFCCC2DC),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // List scores ordered by value
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    scoresList.forEachIndexed { idx, record ->
                        val dateFormatted = remember(record.timestamp) {
                            val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                            sdf.format(Date(record.timestamp))
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1C1B1F))
                                .border(1.dp, Color(0xFF49454F), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Rank Label
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .background(
                                            when (idx) {
                                                0 -> Color(0xFFD0BCFF)
                                                1 -> Color(0xFFB4E197)
                                                2 -> Color(0xFFE6E1E5)
                                                else -> Color(0xFF49454F)
                                            },
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${idx + 1}",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF1C1B1F)
                                    )
                                }

                                Text(
                                    text = "${record.score} PTS",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            // Detail configuration string
                            Text(
                                text = "${record.themeUsed} • ${record.speedUsed} • $dateFormatted",
                                fontSize = 9.sp,
                                color = Color(0xFFCCC2DC)
                            )
                        }
                    }
                }
            }
        }
    }
}
