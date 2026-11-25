package com.example.progressivegym
import android.app.Application
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Calendar
import androidx.compose.material3.Switch
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.draw.clip
import kotlin.math.abs
import kotlin.math.roundToInt

// ======================================================================
// MainActivity
// ======================================================================

class MainActivity : ComponentActivity() {
    private val viewModel: WorkoutViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WorkoutAppTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }
}

// ======================================================================
// Theme
// ======================================================================

@Composable
fun WorkoutAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF00E5FF),
            onPrimary = Color.Black,
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            onBackground = Color.White,
            onSurface = Color.White,
            onSurfaceVariant = Color(0xFFB0B0B0)
        ),
        content = content
    )
}

// ======================================================================
// Model
// ======================================================================

data class Exercise(
    @SerializedName("name") val name: String = "",
    @SerializedName("sets") val sets: String = "",
    @SerializedName("rest") val rest: String = "",
    @SerializedName("style") val style: String = "",
    @SerializedName("facts") val facts: List<String> = emptyList(),
    @SerializedName("setsCount") val setsCount: Int = 0
)

data class DaySchedule(
    @SerializedName("Week A") val weekA: List<Exercise> = emptyList(),
    @SerializedName("Week B") val weekB: List<Exercise> = emptyList()
)

typealias WorkoutData = Map<String, Map<String, List<Exercise>>>

/** Количество подходов из схемы вида "4x6-10", "3", "2xMAX". */
fun parseSetsCount(sets: String): Int =
    Regex("""^\s*(\d+)""").find(sets)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 20) ?: 3

/** Сколько подходов нужно ввести за тренировку. */
val Exercise.plannedSets: Int
    get() = if (setsCount > 0) setsCount else parseSetsCount(sets)

// ======================================================================
// Util
// ======================================================================

data class ParsedSet(val weight: Float?, val value: Float) {
    /** Оценка силы подхода: с весом - расчётный 1ПМ (Эпли), без веса - само число (сек/повторы). */
    val score: Float
        get() = if (weight != null && weight > 0f) weight * (1f + value / 30f) else value

    /** Объём подхода: вес x повторы (или само число, если веса нет). */
    val volume: Float
        get() = if (weight != null && weight > 0f) weight * value else value
}

data class ParsedFact(val sets: List<ParsedSet>) {
    val peak: Float get() = sets.maxOf { it.score }
    val volume: Float get() = sets.sumOf { it.volume.toDouble() }.toFloat()

    /** Изменение силы от первого к последнему подходу, % (null, если подход один). */
    val fatiguePct: Float?
        get() {
            if (sets.size < 2) return null
            val first = sets.first().score
            if (first <= 0f) return null
            return (sets.last().score - first) / first * 100f
        }
}

object FactParser {
    private val numberRegex = Regex("""\d+(?:\.\d+)?""")
    // Разделители подходов: ";" , перевод строки или запятая с пробелом ("42x11, 42x9")
    private val setSeparator = Regex("""\s*(?:;|\n|,\s+)\s*""")

    /** Разбирает запись тренировки: "42x11, 42x9, 42x8", "42кг на 8", "24.5 сек", "8". */
    fun parse(fact: String): ParsedFact? {
        val sets = fact.split(setSeparator).mapNotNull { parseSet(it) }
        return if (sets.isEmpty()) null else ParsedFact(sets)
    }

    fun parseSet(raw: String): ParsedSet? {
        val nums = numberRegex.findAll(raw.replace(',', '.'))
            .mapNotNull { it.value.toFloatOrNull() }
            .toList()
        return when {
            nums.size >= 2 -> ParsedSet(nums[0], nums[1])
            nums.size == 1 -> ParsedSet(null, nums[0])
            else -> null
        }
    }

    /** Пик тренировки (null, если запись нечисловая: "-", "замена"). */
    fun parseFactScore(fact: String): Float? = parse(fact)?.peak
}

fun formatNum(v: Float): String =
    if (v % 1f == 0f) v.toInt().toString() else String.format(java.util.Locale.US, "%.1f", v)

// ======================================================================
// Data
// ======================================================================

object DefaultData {
    val initialData: WorkoutData = mapOf(
        "Понедельник" to mapOf(
            "Week A" to emptyList(),
            "Week B" to emptyList()
        ),
        "Вторник" to mapOf(
            "Week A" to listOf(
                Exercise("Жим лежа", "4x6-10", "60-90s", "ПРОГРЕССИЯ", listOf("42x6", "42x8", "100x8", "42x10", "42x10", "42x8", "42x11"), setsCount = 4),
                Exercise("Подтягивания классика", "4xMAX", "60-90s", "ПРОГРЕССИЯ", listOf("15x8", "15x7", "15x10", "17.5x8", "20x6", "20x8", "20x8"), setsCount = 4),
                Exercise("Жим гантелей 30°", "3x10-12", "60-90s", "-", listOf("26x8", "26x10", "26x12", "28x7", "28x9", "28x8", "26x16"), setsCount = 3),
                Exercise("Тяга нижнего блока", "3x12", "60-90s", "ПРОГРЕССИЯ", listOf("65x10", "67x10", "68x12", "70x10", "70x10", "70x10"), setsCount = 3),
                Exercise("Кроссовер", "2x10-15", "60s", "ПАМП", listOf("10x10", "15x12", "20x10", "20x11", "20x12", "20x12"), setsCount = 2),
                Exercise("Гиперэкстензия", "2xMAX", "60s", "КОНТРОЛЬ", listOf("10x8", "10x12", "10x12", "10x12", "10x13"), setsCount = 2)
            ),
            "Week B" to listOf(
                Exercise("Жим лежа 30°", "4x6-10", "60-90s", "ПРОГРЕССИЯ", listOf("24x12", "26x12", "28x10", "30x12", "30x12", "26x15", "30x13"), setsCount = 4),
                Exercise("Рычажная тяга", "4x8-12", "60-90s", "ПРОГРЕССИЯ", listOf("30x10", "32.5x10", "33.75x10", "40x6", "35x10", "20x10", "35x6"), setsCount = 4),
                Exercise("Хаммер", "3x10-12", "60-90s", "-", listOf("27.5x12", "30x10", "30x12", "30x11", "30x12", "20x10", "35x6"), setsCount = 3),
                Exercise("Тяга верх. блока шир. хват", "3x12", "60-90s", "-", listOf("60x12", "60x12", "63x10", "65x10", "замена", "70x10", "70x12"), setsCount = 3),
                Exercise("Бабочка", "2x10-15", "60s", "ПАМП", listOf("-", "55x12", "60x10", "60x12", "60x12", "65x10"), setsCount = 2),
                Exercise("Гиперэкстензия", "2xMAX", "60s", "КОНТРОЛЬ", listOf("-", "10x12", "10x12", "10x14", "15x11"), setsCount = 2)
            )
        ),
        "Среда" to mapOf(
            "Week A" to emptyList(),
            "Week B" to emptyList()
        ),
        "Четверг" to mapOf(
            "Week A" to listOf(
                Exercise("Жим ногами", "4x10-15", "2-3m", "-", listOf("150x10", "150x15", "160x12", "165x15", "175x8", "175x10", "175x12", "175x12", "175x15", "175x15", "175x15", "175x11"), setsCount = 4),
                Exercise("Сгибание ног", "4x12-15", "90s", "-", listOf("60x10", "60x12", "65x10", "65x12", "65x14", "70x10", "70x10", "70x10", "70x12", "70x12", "70x12", "65x10"), setsCount = 4),
                Exercise("Разгибание ног", "3x15-20", "90s", "-", listOf("55x10", "60x15", "65x12", "65x15", "65x14", "65x14", "70x12", "70x12", "70x12", "65x10"), setsCount = 3),
                Exercise("Голень", "4x15-20", "60s", "-", listOf("40x20", "60x12", "60x13", "60x20", "60x20", "60x24", "60x24", "60x24", "60x24", "60x24", "60x27", "60x24"), setsCount = 4),
                Exercise("Л-сит", "3", "60-90s", "ПРОГРЕССИЯ", listOf("15 сек", "20 сек", "22 сек", "24 сек", "20 сек", "22 сек", "24.5 сек", "24.5 сек", "20 сек", "20 сек", "исключено"), setsCount = 3),
                Exercise("Подъем ног в висе скручивание к корпусу", "3xMAX", "60-90s", "КОНТРОЛЬ", listOf("8", "12", "12", "14", "0", "14", "16", "18", "18", "18", "19", "16", "20"), setsCount = 3)
            ),
            "Week B" to emptyList()
        ),
        "Пятница" to mapOf(
            "Week A" to emptyList(),
            "Week B" to emptyList()
        ),
        "Суббота" to mapOf(
            "Week A" to listOf(
                Exercise("Жим Арнольда", "4x10-15", "2-2.5m", "ПРОГРЕССИЯ", listOf("16x14", "18x8", "18x8"), setsCount = 4),
                Exercise("Брусья с весом", "3x8-12", "2-3m", "ПРОГРЕССИЯ", listOf("15x10", "15x12", "17.5x10"), setsCount = 3),
                Exercise("Махи гант. в стороны", "4x12-15", "60-90s", "ЭГО-ЛИФТИНГ", listOf("12x15", "12x16", "12x16"), setsCount = 4),
                Exercise("Кривой гриф на бицепс", "4x10-15", "90s", "ЧИТИНГ", listOf("47.5x8", "47.5x8", "47.5x9"), setsCount = 4),
                Exercise("Французский жим", "3x12-15", "90s", "ПРОГРЕССИЯ", listOf("-", "27.5x10"), setsCount = 3),
                Exercise("Молотки", "2x10", "90s", "ДРОП-СЕТ", listOf("16x8", "16x10"), setsCount = 2),
                Exercise("Разгибания на блоке", "2x12", "90s", "ДРОП-СЕТ", listOf("30x10"), setsCount = 2),
                Exercise("Лицевая тяга", "3x15", "60s", "ДРОП-СЕТ", listOf("-"), setsCount = 3)
            ),
            "Week B" to listOf(
                Exercise("Жим арнольда", "4x8-12", "2-2.5m", "ПРОГРЕССИЯ", listOf("16x14", "18x8", "16x11"), setsCount = 4),
                Exercise("Брусья с весом", "3x8-12", "2-3m", "ПРОГРЕССИЯ", listOf("20x12", "20x14", "20x14"), setsCount = 3),
                Exercise("Махи гант. в стороны", "4x12-15", "60-90s", "КОНТРОЛЬ", listOf("12x16", "12x16", "12x16"), setsCount = 4),
                Exercise("бицепс машина", "4x10-15", "90s", "-", listOf("40x8", "40x9", "40x9"), setsCount = 4),
                Exercise("Разгибания на блоке", "3x12-15", "90s", "-", listOf("40x12", "40x14", "65x8"), setsCount = 3),
                Exercise("Молотки", "2x12", "90s", "-", listOf("16x10", "16x10", "16x10"), setsCount = 2),
                Exercise("французский жим", "2x15", "90s", "-", listOf("37.5x8", "37.5x8", "37.5x8"), setsCount = 2),
                Exercise("Лицевая тяга", "3x15", "60s", "ДРОП-СЕТ", listOf("45x12", "-", "45x12"), setsCount = 3)
            )
        ),
        "Воскресенье" to mapOf(
            "Week A" to emptyList(),
            "Week B" to emptyList()
        )
    )
}

/** Приводит данные (в том числе сохранённые ранее на устройстве) к актуальному формату. */
object DataMigration {
    fun normalize(data: WorkoutData): WorkoutData =
        data.mapValues { (_, weeks) ->
            weeks.mapValues { (_, list) -> list.map { normalizeExercise(it) } }
        }

    private fun normalizeExercise(ex: Exercise): Exercise = ex.copy(
        setsCount = if (ex.setsCount > 0) ex.setsCount else parseSetsCount(ex.sets),
        facts = ex.facts.map { normalizeFact(it) }
    )

    /** "42кг на 8" -> "42x8"; записи без веса и нечисловые остаются как есть. */
    private fun normalizeFact(fact: String): String {
        val parsed = FactParser.parse(fact) ?: return fact
        if (parsed.sets.any { it.weight == null }) return fact
        return parsed.sets.joinToString(", ") { "${formatNum(it.weight!!)}x${formatNum(it.value)}" }
    }
}

class WorkoutRepository(private val context: Context) {
    private val gson = Gson()
    private val file: File
        get() = File(context.filesDir, "workout_data.json")

    fun loadData(): WorkoutData {
        if (!file.exists()) {
            val initial = DataMigration.normalize(DefaultData.initialData)
            saveData(initial)
            return initial
        }

        return try {
            val json = file.readText(Charsets.UTF_8)
            val type = object : TypeToken<Map<String, Map<String, List<Exercise>>>>() {}.type
            val raw: WorkoutData = gson.fromJson(json, type) ?: DefaultData.initialData
            val normalized = DataMigration.normalize(raw)
            if (normalized != raw) saveData(normalized)
            normalized
        } catch (e: Exception) {
            e.printStackTrace()
            DataMigration.normalize(DefaultData.initialData)
        }
    }

    fun saveData(data: WorkoutData) {
        try {
            val json = gson.toJson(data)
            file.writeText(json, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

// ======================================================================
// ViewModel
// ======================================================================

class WorkoutViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = WorkoutRepository(application)

    private val _data = MutableStateFlow<WorkoutData>(emptyMap())
    val data: StateFlow<WorkoutData> = _data.asStateFlow()

    private val _selectedDay = MutableStateFlow(getTodayDayName())
    val selectedDay: StateFlow<String> = _selectedDay.asStateFlow()

    private val _selectedWeek = MutableStateFlow("Week A")
    val selectedWeek: StateFlow<String> = _selectedWeek.asStateFlow()

    private val _selectedStatExercise = MutableStateFlow<String?>(null)
    val selectedStatExercise: StateFlow<String?> = _selectedStatExercise.asStateFlow()

    val days = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")
    val weeks = listOf("Week A", "Week B")

    init {
        loadData()
    }

    private fun getTodayDayName(): String {
        val calendar = Calendar.getInstance()
        return when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "Понедельник"
            Calendar.TUESDAY -> "Вторник"
            Calendar.WEDNESDAY -> "Среда"
            Calendar.THURSDAY -> "Четверг"
            Calendar.FRIDAY -> "Пятница"
            Calendar.SATURDAY -> "Суббота"
            Calendar.SUNDAY -> "Воскресенье"
            else -> "Вторник"
        }
    }

    fun loadData() {
        _data.value = repository.loadData()
        updateDefaultStatExercise()
    }

    fun selectDay(day: String) {
        _selectedDay.value = day
    }

    fun selectWeek(week: String) {
        _selectedWeek.value = week
    }

    fun selectStatExercise(name: String) {
        _selectedStatExercise.value = name
    }

    fun getAllExerciseNames(): List<String> {
        val names = mutableSetOf<String>()
        _data.value.values.forEach { dayMap ->
            dayMap.values.forEach { exerciseList ->
                exerciseList.forEach { ex ->
                    if (ex.name.isNotBlank()) {
                        names.add(ex.name)
                    }
                }
            }
        }
        return names.sorted()
    }

    private fun updateDefaultStatExercise() {
        val names = getAllExerciseNames()
        if (names.isNotEmpty() && (_selectedStatExercise.value == null || _selectedStatExercise.value !in names)) {
            _selectedStatExercise.value = names.first()
        }
    }

    fun saveExercise(day: String, week: String, index: Int?, name: String, setsCount: Int, reps: String, rest: String, style: String) {
        val currentData = _data.value.toMutableMap()
        val dayMap = currentData[day]?.toMutableMap() ?: mutableMapOf("Week A" to emptyList(), "Week B" to emptyList())
        val list = (dayMap[week] ?: emptyList()).toMutableList()

        val count = setsCount.coerceIn(1, 20)
        val scheme = if (reps.isBlank()) "$count" else "${count}x${reps.trim()}"

        if (index != null && index in list.indices) {
            val existing = list[index]
            list[index] = existing.copy(
                name = name.ifBlank { "Упражнение" },
                sets = scheme,
                setsCount = count,
                rest = rest.ifBlank { "60s" },
                style = style.ifBlank { "-" }
            )
        } else {
            list.add(
                Exercise(
                    name = name.ifBlank { "Упражнение" },
                    sets = scheme,
                    rest = rest.ifBlank { "60s" },
                    style = style.ifBlank { "-" },
                    setsCount = count
                )
            )
        }

        dayMap[week] = list
        currentData[day] = dayMap
        _data.value = currentData
        repository.saveData(currentData)
        updateDefaultStatExercise()
    }

    /** Перемещает упражнение внутри выбранного дня и недели, сразу сохраняя новый порядок. */
    fun moveExercise(day: String, week: String, fromIndex: Int, toIndex: Int) {
        val currentData = _data.value.toMutableMap()
        val dayMap = currentData[day]?.toMutableMap() ?: return
        val list = (dayMap[week] ?: return).toMutableList()

        if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) return

        val exercise = list.removeAt(fromIndex)
        list.add(toIndex, exercise)
        dayMap[week] = list
        currentData[day] = dayMap
        _data.value = currentData
        repository.saveData(currentData)
    }

    fun deleteExercise(day: String, week: String, index: Int) {
        val currentData = _data.value.toMutableMap()
        val dayMap = currentData[day]?.toMutableMap() ?: return
        val list = (dayMap[week] ?: return).toMutableList()

        if (index in list.indices) {
            list.removeAt(index)
            dayMap[week] = list
            currentData[day] = dayMap
            _data.value = currentData
            repository.saveData(currentData)
            updateDefaultStatExercise()
        }
    }

    fun addFact(day: String, week: String, exerciseIndex: Int, text: String) {
        val factStr = text.trim()
        if (factStr.isBlank()) return

        val currentData = _data.value.toMutableMap()
        val dayMap = currentData[day]?.toMutableMap() ?: return
        val list = (dayMap[week] ?: return).toMutableList()

        if (exerciseIndex in list.indices) {
            val ex = list[exerciseIndex]
            list[exerciseIndex] = ex.copy(facts = ex.facts + factStr)
            dayMap[week] = list
            currentData[day] = dayMap
            _data.value = currentData
            repository.saveData(currentData)
        }
    }

    fun deleteFact(day: String, week: String, exerciseIndex: Int, factIndex: Int) {
        val currentData = _data.value.toMutableMap()
        val dayMap = currentData[day]?.toMutableMap() ?: return
        val list = (dayMap[week] ?: return).toMutableList()

        if (exerciseIndex in list.indices) {
            val ex = list[exerciseIndex]
            if (factIndex in ex.facts.indices) {
                val updatedFacts = ex.facts.toMutableList().apply { removeAt(factIndex) }
                list[exerciseIndex] = ex.copy(facts = updatedFacts)
                dayMap[week] = list
                currentData[day] = dayMap
                _data.value = currentData
                repository.saveData(currentData)
            }
        }
    }
}

// ======================================================================
// UI: MainScreen
// ======================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: WorkoutViewModel) {
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    var showReportDialog by rememberSaveable { mutableStateOf(false) }
    val data by viewModel.data.collectAsState()
    val tabs = listOf("Тренировки", "Статистика")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Прогрессии Тренировок",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    TextButton(onClick = { showReportDialog = true }) {
                        Text(
                            text = "Отчёт",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E5FF)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E1E1E),
                    titleContentColor = Color.White
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = Color(0xFF1E1E1E),
                contentColor = Color.White
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = {
                            Text(
                                title,
                                fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            when (selectedTabIndex) {
                0 -> WorkoutTab(viewModel = viewModel)
                1 -> StatsTab(viewModel = viewModel)
            }
        }
    }

    if (showReportDialog) {
        WorkoutReportDialog(
            data = data,
            days = viewModel.days,
            weeks = viewModel.weeks,
            onDismiss = { showReportDialog = false }
        )
    }
}

// ======================================================================
// UI: WorkoutTab + ExerciseCard
// ======================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkoutTab(viewModel: WorkoutViewModel) {
    val data by viewModel.data.collectAsState()
    val selectedDay by viewModel.selectedDay.collectAsState()
    val selectedWeek by viewModel.selectedWeek.collectAsState()

    var showExerciseDialog by rememberSaveable { mutableStateOf(false) }
    var editingExerciseIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    var activeFactsExerciseIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    val currentExercises = data[selectedDay]?.get(selectedWeek) ?: emptyList()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        // Dropdown Selectors Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Day Dropdown
            var dayMenuExpanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.weight(1.2f)) {
                OutlinedButton(
                    onClick = { dayMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = selectedDay, fontSize = 13.sp)
                }
                DropdownMenu(
                    expanded = dayMenuExpanded,
                    onDismissRequest = { dayMenuExpanded = false }
                ) {
                    viewModel.days.forEach { day ->
                        DropdownMenuItem(
                            text = { Text(day) },
                            onClick = {
                                viewModel.selectDay(day)
                                dayMenuExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Week Dropdown
            var weekMenuExpanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.weight(0.8f)) {
                OutlinedButton(
                    onClick = { weekMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = selectedWeek, fontSize = 13.sp)
                }
                DropdownMenu(
                    expanded = weekMenuExpanded,
                    onDismissRequest = { weekMenuExpanded = false }
                ) {
                    viewModel.weeks.forEach { week ->
                        DropdownMenuItem(
                            text = { Text(week) },
                            onClick = {
                                viewModel.selectWeek(week)
                                weekMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Title + Add Button Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "План упражнений",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Button(
                onClick = {
                    editingExerciseIndex = null
                    showExerciseDialog = true
                }
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Добавить")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Exercise List
        if (currentExercises.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Нет упражнений на этот день. Нажмите '+ Добавить'.",
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(currentExercises) { index, exercise ->
                    ExerciseCard(
                        exercise = exercise,
                        number = index + 1,
                        canMoveUp = index > 0,
                        canMoveDown = index < currentExercises.lastIndex,
                        onMoveUp = {
                            viewModel.moveExercise(selectedDay, selectedWeek, index, index - 1)
                        },
                        onMoveDown = {
                            viewModel.moveExercise(selectedDay, selectedWeek, index, index + 1)
                        },
                        onEdit = {
                            editingExerciseIndex = index
                            showExerciseDialog = true
                        },
                        onDelete = {
                            viewModel.deleteExercise(selectedDay, selectedWeek, index)
                        },
                        onOpenFacts = {
                            activeFactsExerciseIndex = index
                        }
                    )
                }
            }
        }
    }

    // Exercise Dialog (Add/Edit)
    if (showExerciseDialog) {
        val initialExercise = editingExerciseIndex?.let { currentExercises.getOrNull(it) }
        ExerciseDialog(
            initialExercise = initialExercise,
            onDismiss = { showExerciseDialog = false },
            onSave = { name, setsCount, reps, rest, style ->
                viewModel.saveExercise(
                    day = selectedDay,
                    week = selectedWeek,
                    index = editingExerciseIndex,
                    name = name,
                    setsCount = setsCount,
                    reps = reps,
                    rest = rest,
                    style = style
                )
            }
        )
    }

    // Facts Dialog
    activeFactsExerciseIndex?.let { factExIdx ->
        val exercise = currentExercises.getOrNull(factExIdx)
        if (exercise != null) {
            FactsDialog(
                exercise = exercise,
                onDismiss = { activeFactsExerciseIndex = null },
                onAddFact = { text ->
                    viewModel.addFact(selectedDay, selectedWeek, factExIdx, text)
                },
                onDeleteFact = { factIdx ->
                    viewModel.deleteFact(selectedDay, selectedWeek, factExIdx, factIdx)
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExerciseCard(
    exercise: Exercise,
    number: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenFacts: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenFacts() },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$number.",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF00E5FF)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = exercise.name.ifBlank { "Упражнение" },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = "Переместить выше",
                            tint = if (canMoveUp) Color.LightGray else Color(0xFF555555)
                        )
                    }
                    IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Переместить ниже",
                            tint = if (canMoveDown) Color.LightGray else Color(0xFF555555)
                        )
                    }
                    IconButton(onClick = onEdit) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = "Редактировать",
                            tint = Color.LightGray
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "Удалить",
                            tint = Color(0xFFEF5350)
                        )
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF333333))
            Spacer(modifier = Modifier.height(6.dp))

            // Details Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Подходы: ${exercise.sets}", fontSize = 12.sp, color = Color.LightGray)
                Text(text = "Отдых: ${exercise.rest}", fontSize = 12.sp, color = Color.LightGray)
                Text(text = "Стиль: ${exercise.style}", fontSize = 12.sp, color = Color(0xFFFFD54F))
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Факты (🟢 Рост/Рекорд  🟡 Равно  🔴 Спад):",
                fontSize = 10.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(4.dp))

            // Fact Chips Row
            val facts = exercise.facts
            if (facts.isEmpty()) {
                Text(
                    text = "Нет фактов (нажмите для добавления)",
                    fontSize = 11.sp,
                    fontStyle = FontStyle.Italic,
                    color = Color.DarkGray
                )
            } else {
                val parsed = facts.map { FactParser.parse(it) }
                val maxScore = parsed.mapNotNull { it?.peak }.maxOrNull() ?: 0f
                val prevPeaks = parsed.indices.map { i ->
                    parsed.take(i).mapNotNull { it?.peak }.lastOrNull()
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    facts.forEachIndexed { fIdx, fVal ->
                        val score = parsed[fIdx]?.peak
                        val prevScore = prevPeaks[fIdx]
                        val changePct =
                            if (score != null && prevScore != null && prevScore > 0f)
                                (score - prevScore) / prevScore * 100f
                            else null

                        val chipBgColor = when {
                            score == null -> Color(0xFF424242)
                            changePct == null -> Color(0xFF37474F)
                            changePct > 1f -> if (score >= maxScore) Color(0xFF2E7D32) else Color(0xFF1B5E20)
                            changePct < -1f -> Color(0xFFB71C1C)
                            else -> Color(0xFFE65100)
                        }
                        val arrow = when {
                            changePct == null -> ""
                            changePct > 1f -> "  ▲${changePct.roundToInt()}%"
                            changePct < -1f -> "  ▼${abs(changePct).roundToInt()}%"
                            else -> "  ＝"
                        }

                        Box(
                            modifier = Modifier
                                .background(chipBgColor, shape = RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Ф${fIdx + 1}: $fVal$arrow",
                                fontSize = 11.sp,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

// ======================================================================
// UI: Text Report
// ======================================================================

/** Формирует полный текстовый отчёт по каждому дню, неделе, упражнению и истории прогресса. */
fun buildWorkoutReport(
    data: WorkoutData,
    days: List<String>,
    weeks: List<String>
): String {
    val report = StringBuilder()
    report.append("ОТЧЁТ ПО ТРЕНИРОВКАМ\n")
    report.append("====================\n")

    days.forEach { day ->
        report.append("\n").append(day.uppercase()).append("\n")
        report.append("-".repeat(day.length)).append("\n")

        weeks.forEach { week ->
            report.append("\n  ").append(week).append("\n")
            val exercises = data[day]?.get(week).orEmpty()

            if (exercises.isEmpty()) {
                report.append("    Нет упражнений\n")
            } else {
                exercises.forEachIndexed { index, exercise ->
                    report.append("\n    ")
                        .append(index + 1)
                        .append(". ")
                        .append(exercise.name.ifBlank { "Упражнение" })
                        .append("\n")
                    report.append("       План: ").append(exercise.sets.ifBlank { "—" })
                        .append(" | отдых: ").append(exercise.rest.ifBlank { "—" })
                        .append(" | стиль: ").append(exercise.style.ifBlank { "—" })
                        .append("\n")

                    if (exercise.facts.isEmpty()) {
                        report.append("       Прогресс: записей пока нет\n")
                    } else {
                        report.append("       История записей:\n")
                        val parsedRecords = mutableListOf<ParsedFact>()

                        exercise.facts.forEachIndexed { factIndex, fact ->
                            report.append("         Ф").append(factIndex + 1)
                                .append(": ").append(fact)
                            val parsed = FactParser.parse(fact)
                            if (parsed != null) {
                                parsedRecords.add(parsed)
                                report.append(" | пик: ").append(formatNum(parsed.peak))
                                    .append(" | объём: ").append(formatNum(parsed.volume))
                            } else {
                                report.append(" | без числового разбора")
                            }
                            report.append("\n")
                        }

                        if (parsedRecords.isEmpty()) {
                            report.append("       Сводка прогресса: нет числовых записей\n")
                        } else {
                            val first = parsedRecords.first()
                            val last = parsedRecords.last()
                            val bestPeak = parsedRecords.maxOf { it.peak }
                            report.append("       Рекорд по пику: ").append(formatNum(bestPeak)).append("\n")

                            if (parsedRecords.size >= 2) {
                                val overallChange = if (first.peak > 0f) {
                                    (last.peak - first.peak) / first.peak * 100f
                                } else null
                                report.append("       Первая → последняя запись: ")
                                if (overallChange != null) {
                                    val sign = if (overallChange > 0f) "+" else ""
                                    report.append(sign)
                                        .append(String.format(java.util.Locale.US, "%.1f", overallChange))
                                        .append("% по пику")
                                } else {
                                    report.append("недостаточно данных для процента")
                                }
                                report.append(" (")
                                    .append(formatNum(first.peak))
                                    .append(" → ")
                                    .append(formatNum(last.peak))
                                    .append(")\n")

                                val previous = parsedRecords[parsedRecords.lastIndex - 1]
                                val recentChange = if (previous.peak > 0f) {
                                    (last.peak - previous.peak) / previous.peak * 100f
                                } else null
                                report.append("       Последняя к предыдущей: ")
                                if (recentChange != null) {
                                    val sign = if (recentChange > 0f) "+" else ""
                                    report.append(sign)
                                        .append(String.format(java.util.Locale.US, "%.1f", recentChange))
                                        .append("% по пику")
                                } else {
                                    report.append("недостаточно данных")
                                }
                                report.append("\n")
                            } else {
                                report.append("       Для расчёта динамики нужна ещё одна числовая запись\n")
                            }
                        }
                    }
                }
            }
        }
    }

    return report.toString().trim()
}

@Composable
fun WorkoutReportDialog(
    data: WorkoutData,
    days: List<String>,
    weeks: List<String>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val report = remember(data, days, weeks) {
        buildWorkoutReport(data, days, weeks)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Текстовый отчёт", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = report,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = Color.White
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Отчёт по тренировкам", report))
                    Toast.makeText(context, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("Копировать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}

// ======================================================================
// UI: StatsTab
// ======================================================================

@Composable
fun StatsTab(viewModel: WorkoutViewModel) {
    val data by viewModel.data.collectAsState()
    val selectedExerciseName by viewModel.selectedStatExercise.collectAsState()
    val allExerciseNames = remember(data) { viewModel.getAllExerciseNames() }
    var showVolume by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Dropdown to Select Exercise
        var menuExpanded by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = selectedExerciseName ?: "Выберите упражнение")
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                allExerciseNames.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            viewModel.selectStatExercise(name)
                            menuExpanded = false
                        }
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        if (selectedExerciseName.isNullOrEmpty()) {
            Text(
                text = "Добавьте упражнения для анализа.",
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        // Все числовые записи выбранного упражнения (текст + разобранные подходы)
        val entries = remember(data, selectedExerciseName) {
            val result = mutableListOf<Pair<String, ParsedFact>>()
            data.values.forEach { dayMap ->
                dayMap.values.forEach { exerciseList ->
                    exerciseList.forEach { ex ->
                        if (ex.name == selectedExerciseName) {
                            ex.facts.forEach { f ->
                                FactParser.parse(f)?.let { result.add(f to it) }
                            }
                        }
                    }
                }
            }
            result
        }

        if (entries.isEmpty()) {
            Text(
                text = "Нет записей по упражнению '$selectedExerciseName'",
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        val scores = entries.map { if (showVolume) it.second.volume else it.second.peak }
        val maxScore = scores.maxOrNull() ?: 0f
        val bestFact = entries[scores.indexOf(maxScore).coerceAtLeast(0)].first

        val lastScore = scores.last()
        val prevScore = if (scores.size > 1) scores[scores.size - 2] else lastScore
        val changePct = if (prevScore > 0f) (lastScore - prevScore) / prevScore * 100f else 0f

        val (dynamicsText, dynamicsColor) = when {
            changePct > 1f -> "↗ Рост +${changePct.roundToInt()}%" to Color(0xFF66BB6A)
            changePct < -1f -> "↘ Спад ${changePct.roundToInt()}%" to Color(0xFFEF5350)
            else -> "→ Без изменений" to Color(0xFFFFCA28)
        }

        val lastFact = entries.last().second
        val fatigue = lastFact.fatiguePct
        val fatigueText = fatigue?.let { (if (it > 0f) "+" else "") + "${it.roundToInt()}%" } ?: "—"
        val fatigueColor = when {
            fatigue == null -> Color.Gray
            fatigue >= -10f -> Color(0xFF66BB6A)
            fatigue >= -25f -> Color(0xFFFFCA28)
            else -> Color(0xFFEF5350)
        }

        // Summary Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "📊 Анализ: $selectedExerciseName",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White
                )
                HorizontalDivider(color = Color(0xFF333333), modifier = Modifier.padding(vertical = 8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(text = "Рекорд (PB)", fontSize = 10.sp, color = Color.Gray)
                        Text(
                            text = bestFact,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF81C784)
                        )
                    }
                    Column {
                        Text(text = "Тренировок", fontSize = 10.sp, color = Color.Gray)
                        Text(
                            text = "${entries.size}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Column {
                        Text(text = "Динамика", fontSize = 10.sp, color = Color.Gray)
                        Text(
                            text = dynamicsText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = dynamicsColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(text = "Пик последней", fontSize = 10.sp, color = Color.Gray)
                        Text(
                            text = formatNum(lastFact.peak),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Column {
                        Text(text = "Объём последней", fontSize = 10.sp, color = Color.Gray)
                        Text(
                            text = formatNum(lastFact.volume),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Column {
                        Text(text = "1-й → посл. подход", fontSize = 10.sp, color = Color.Gray)
                        Text(
                            text = fatigueText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = fatigueColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Пик: с весом - расчётный 1ПМ (формула Эпли), без веса - само число. " +
                            "Объём: сумма вес × повторы по всем подходам. " +
                            "Сильная просадка к последнему подходу = подходы были не «расслабленные».",
                    fontSize = 10.sp,
                    color = Color.Gray
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Metric toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!showVolume) {
                Button(onClick = { }, modifier = Modifier.weight(1f)) { Text("Пик (1ПМ)") }
                OutlinedButton(onClick = { showVolume = true }, modifier = Modifier.weight(1f)) { Text("Объём") }
            } else {
                OutlinedButton(onClick = { showVolume = false }, modifier = Modifier.weight(1f)) { Text("Пик (1ПМ)") }
                Button(onClick = { }, modifier = Modifier.weight(1f)) { Text("Объём") }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Progress Chart
        ProgressChart(
            scores = scores,
            title = (if (showVolume) "Объём" else "Пик") + " (макс ${formatNum(maxScore)}):"
        )
    }
}

// ======================================================================
// UI: ProgressChart
// ======================================================================

@Composable
fun ProgressChart(
    scores: List<Float>,
    title: String = "График прогресса нагрузки:",
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), shape = RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Column {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            if (scores.isEmpty()) {
                Text(
                    text = "Нет данных для отображения графика",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(vertical = 32.dp)
                )
            } else {
                val cyanColor = Color(0xFF00E5FF)
                val gridColor = Color(0xFF333333)

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                ) {
                    val width = size.width
                    val height = size.height
                    val paddingLeft = 40f
                    val paddingBottom = 30f
                    val chartWidth = width - paddingLeft
                    val chartHeight = height - paddingBottom

                    val maxScore = (scores.maxOrNull() ?: 100f).coerceAtLeast(10f)
                    val minScore = 0f

                    // Grid Lines
                    val gridLines = 4
                    for (i in 0..gridLines) {
                        val y = chartHeight * i / gridLines
                        drawLine(
                            color = gridColor,
                            start = Offset(paddingLeft, y),
                            end = Offset(width, y),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    // Calculate Points
                    val points = scores.mapIndexed { index, score ->
                        val x = if (scores.size > 1) {
                            paddingLeft + (index.toFloat() / (scores.size - 1)) * chartWidth
                        } else {
                            paddingLeft + chartWidth / 2f
                        }
                        val normalizedY = (score - minScore) / (maxScore - minScore)
                        val y = chartHeight - (normalizedY * chartHeight)
                        Offset(x, y)
                    }

                    if (points.isNotEmpty()) {
                        val linePath = Path().apply {
                            moveTo(points.first().x, points.first().y)
                            for (i in 0 until points.size - 1) {
                                val p1 = points[i]
                                val p2 = points[i + 1]
                                val controlPoint1 = Offset(p1.x + (p2.x - p1.x) / 2f, p1.y)
                                val controlPoint2 = Offset(p1.x + (p2.x - p1.x) / 2f, p2.y)
                                cubicTo(controlPoint1.x, controlPoint1.y, controlPoint2.x, controlPoint2.y, p2.x, p2.y)
                            }
                        }

                        val fillPath = Path().apply {
                            addPath(linePath)
                            lineTo(points.last().x, chartHeight)
                            lineTo(points.first().x, chartHeight)
                            close()
                        }

                        // Gradient Fill Under Line
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    cyanColor.copy(alpha = 0.35f),
                                    cyanColor.copy(alpha = 0.0f)
                                ),
                                startY = 0f,
                                endY = chartHeight
                            )
                        )

                        // Line Stroke
                        drawPath(
                            path = linePath,
                            color = cyanColor,
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                        )

                        // Point Dots
                        points.forEach { point ->
                            drawCircle(
                                color = cyanColor,
                                radius = 4.dp.toPx(),
                                center = point
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 2.dp.toPx(),
                                center = point
                            )
                        }
                    }

                    // Axes
                    drawLine(
                        color = Color.Gray,
                        start = Offset(paddingLeft, 0f),
                        end = Offset(paddingLeft, chartHeight),
                        strokeWidth = 2.dp.toPx()
                    )
                    drawLine(
                        color = Color.Gray,
                        start = Offset(paddingLeft, chartHeight),
                        end = Offset(width, chartHeight),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            }
        }
    }
}

// ======================================================================
// UI: Dialogs
// ======================================================================

@Composable
fun ExerciseDialog(
    initialExercise: Exercise? = null,
    onDismiss: () -> Unit,
    onSave: (name: String, setsCount: Int, reps: String, rest: String, style: String) -> Unit
) {
    val initialReps = remember(initialExercise) {
        Regex("""^\s*\d+\s*[xх×]\s*(.+)$""")
            .find(initialExercise?.sets ?: "")?.groupValues?.get(1)?.trim() ?: ""
    }
    var name by rememberSaveable(initialExercise?.name) { mutableStateOf(initialExercise?.name ?: "") }
    var setsCount by rememberSaveable(initialExercise?.setsCount) { mutableStateOf((initialExercise?.plannedSets ?: 3).toString()) }
    var reps by rememberSaveable(initialReps) { mutableStateOf(initialReps) }
    var rest by rememberSaveable(initialExercise?.rest) { mutableStateOf(initialExercise?.rest ?: "") }
    var style by rememberSaveable(initialExercise?.style) { mutableStateOf(initialExercise?.style ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialExercise != null) "Редактировать упражнение" else "Добавить упражнение") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название упражнения") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = setsCount,
                        onValueChange = { v -> setsCount = v.filter { it.isDigit() }.take(2) },
                        label = { Text("Подходов") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = reps,
                        onValueChange = { reps = it },
                        label = { Text("Повторы (6-10, MAX)") },
                        singleLine = true,
                        modifier = Modifier.weight(1.6f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = rest,
                    onValueChange = { rest = it },
                    label = { Text("Отдых (напр. 60-90s)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = style,
                    onValueChange = { style = it },
                    label = { Text("Стиль (напр. ПРОГРЕССИЯ)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(name, setsCount.toIntOrNull() ?: 3, reps, rest, style)
                    onDismiss()
                }
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Composable
fun FactsDialog(
    exercise: Exercise,
    onDismiss: () -> Unit,
    onAddFact: (text: String) -> Unit,
    onDeleteFact: (factIndex: Int) -> Unit
) {
    val setCount = exercise.plannedSets
    val lastParsed = remember(exercise.facts) {
        exercise.facts.lastOrNull()?.let { FactParser.parse(it) }
    }

    var noWeight by rememberSaveable(exercise.name, setCount) {
        mutableStateOf(lastParsed?.sets?.all { it.weight == null } == true)
    }
    val weights = rememberSaveable(
        exercise.name, setCount,
        saver = listSaver(
            save = { it.toList() },
            restore = { it.toMutableStateList() }
        )
    ) {
        List(setCount) { "" }.toMutableStateList()
    }
    while (weights.size < setCount) weights.add("")
    while (weights.size > setCount) weights.removeAt(weights.lastIndex)

    val repsList = rememberSaveable(
        exercise.name, setCount,
        saver = listSaver(
            save = { it.toList() },
            restore = { it.toMutableStateList() }
        )
    ) {
        List(setCount) { "" }.toMutableStateList()
    }
    while (repsList.size < setCount) repsList.add("")
    while (repsList.size > setCount) repsList.lastIndex.let { if (it >= 0) repsList.removeAt(it) }

    val setRecords = remember(exercise.facts, setCount) {
        if (exercise.facts.isEmpty()) {
            List(setCount) { null }
        } else {
            val lastStr = exercise.facts.last()
            val lastFactSets = lastStr.split(Regex("""\s*(?:;|\n|,\s+)\s*"""))
            if (lastFactSets.size >= setCount || lastFactSets.size > 1) {
                List(setCount) { i -> lastFactSets.getOrNull(i) }
            } else if (exercise.facts.size >= setCount) {
                val recentFacts = exercise.facts.takeLast(setCount)
                List(setCount) { i -> recentFacts.getOrNull(i) }
            } else {
                List(setCount) { i ->
                    if (i < lastFactSets.size) lastFactSets[i]
                    else exercise.facts.getOrNull(i)
                }
            }
        }
    }

    // Собираем строку "42x11, 42x9, 42x8" из заполненных подходов
    val factText = (0 until setCount).mapNotNull { i ->
        val w = weights[i].trim().replace(',', '.')
        val r = repsList[i].trim().replace(',', '.')
        when {
            r.isBlank() -> null
            noWeight || w.isBlank() -> r
            else -> "${w}x$r"
        }
    }.joinToString(", ")
    val preview = FactParser.parse(factText)
    val filled = (0 until setCount).count { repsList[it].isNotBlank() }
    // Для каждого запланированного подхода обязательны повторы;
    // если режим с весом включён — обязателен также вес.
    val allSetsCompleted = filled == setCount &&
            (noWeight || (0 until setCount).all { weights[it].isNotBlank() })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Факты: ${exercise.name}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // History
                if (exercise.facts.isEmpty()) {
                    Text(
                        text = "Нет записей. Добавьте первую ниже.",
                        fontStyle = FontStyle.Italic,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 140.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        exercise.facts.forEachIndexed { index, fact ->
                            val parsed = FactParser.parse(fact)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = "Ф${index + 1}: $fact", fontSize = 13.sp)
                                    if (parsed != null) {
                                        Text(
                                            text = "пик ${formatNum(parsed.peak)} · объём ${formatNum(parsed.volume)}",
                                            fontSize = 10.sp,
                                            color = Color.Gray
                                        )
                                    }
                                }
                                IconButton(onClick = { onDeleteFact(index) }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = "Удалить запись",
                                        tint = Color(0xFFEF5350)
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = "Новая тренировка: $setCount подх. (${exercise.sets})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Без веса (секунды / повторы)",
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = noWeight, onCheckedChange = { noWeight = it })
                }

                for (i in 0 until setCount) {
                    val prevRecord = setRecords.getOrNull(i)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Подход ${i + 1}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color.Gray
                            )
                            if (!prevRecord.isNullOrBlank()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF2C2C2C))
                                        .clickable {
                                            val parsedSet = FactParser.parseSet(prevRecord)
                                            if (parsedSet != null) {
                                                if (!noWeight && parsedSet.weight != null) {
                                                    weights[i] = formatNum(parsedSet.weight)
                                                }
                                                repsList[i] = formatNum(parsedSet.value)
                                            }
                                        }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "пред: ",
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )
                                    Text(
                                        text = prevRecord,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF81C784)
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!noWeight) {
                                OutlinedTextField(
                                    value = weights[i],
                                    onValueChange = { weights[i] = it },
                                    label = { Text("кг") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            OutlinedTextField(
                                value = repsList[i],
                                onValueChange = { repsList[i] = it },
                                label = { Text(if (noWeight) "сек / повт." else "повторы") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = if (noWeight) KeyboardType.Decimal else KeyboardType.Number
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (preview != null)
                        "Заполнено $filled из $setCount · пик ${formatNum(preview.peak)} · объём ${formatNum(preview.volume)}"
                    else
                        "Заполнено $filled из $setCount · необходимо заполнить все подходы",
                    fontSize = 11.sp,
                    color = Color.Gray
                )

                if (lastParsed != null || setRecords.any { !it.isNullOrBlank() }) {
                    TextButton(
                        onClick = {
                            for (i in 0 until setCount) {
                                val rec = setRecords.getOrNull(i)
                                val s = rec?.let { FactParser.parseSet(it) } ?: lastParsed?.sets?.getOrNull(i)
                                if (s != null) {
                                    if (!noWeight && s.weight != null) {
                                        weights[i] = formatNum(s.weight)
                                    }
                                    repsList[i] = formatNum(s.value)
                                }
                            }
                        }
                    ) {
                        Text("Подставить прошлую запись", fontSize = 12.sp)
                    }
                }

                Button(
                    onClick = {
                        onAddFact(factText)
                        for (i in 0 until setCount) {
                            weights[i] = ""
                            repsList[i] = ""
                        }
                    },
                    enabled = allSetsCompleted && factText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Добавить запись")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}

