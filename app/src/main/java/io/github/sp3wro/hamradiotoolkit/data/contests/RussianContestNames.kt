package io.github.sp3wro.hamradiotoolkit.data.contests

/**
 * Tłumaczenie rosyjskich nazw zawodów z QRZ.RU na angielski.
 * Znane nazwy (stan kalendarza z października 2026) mają ręczne tłumaczenia; nowe przechodzą przez
 * słownik typowych słów i transliterację – wynik jest przybliżony, dlatego ekran pokazuje też oryginał.
 */
object RussianContestNames {
    private val CYRILLIC = Regex("[\\u0400-\\u04FF]")

    // Łacińskie litery wyglądające jak cyrylica (np. "Cоревнования" z łacińskim C) w słowach pisanych cyrylicą
    private val LOOKALIKES = mapOf('a' to 'а', 'c' to 'с', 'e' to 'е', 'o' to 'о', 'p' to 'р', 'x' to 'х', 'y' to 'у')

    fun isCyrillic(s: String): Boolean = CYRILLIC.containsMatchIn(s)

    fun translate(name: String): String {
        if (!isCyrillic(name)) return name
        KNOWN[normalize(name)]?.let { return it }
        return fallback(name)
    }

    // ------------------------------------------------------------------ znane nazwy

    private val KNOWN: Map<String, String> = mapOf(
        "ARCK - КАРТОЧНЫЙ ДОМИК" to "ARCK – House of Cards",
        "ARCK - ПАМЯТЬ" to "ARCK – Memory",
        "Соревнования СРР по радиоспорту" to "SRR Radiosport Contest",
        "RCC VHF CONTEST (1 тур)" to "RCC VHF Contest (round 1)",
        "RCC VHF CONTEST (2 тур)" to "RCC VHF Contest (round 2)",
        "АМУР" to "Amur",
        "Весенний Полярный спринт (Arktika-spring)" to "Spring Polar Sprint (Arktika-spring)",
        "Владимирский тест" to "Vladimir Test",
        "Всероссийские соревнования ДФО - телеграф" to "All-Russian Far Eastern Federal District Contest – CW",
        "Всероссийские соревнования ДФО - телефон" to "All-Russian Far Eastern Federal District Contest – SSB",
        "Две столицы" to "Two Capitals",
        "День пограничника" to "Border Guard Day",
        "Есенинская Русь" to "Yesenin's Rus",
        "За други своя" to "For One's Friends",
        "За тех, кто в море" to "For Those at Sea",
        "Защитник Отечества" to "Defender of the Fatherland",
        "Зимние Санкт-Петербургские открытые УКВ-соревнования" to "St. Petersburg Open Winter VHF Contest",
        "Зоя" to "Zoya",
        "Идёт охота на Волков" to "The Wolf Hunt Is On",
        "Калужские УКВ соревнования" to "Kaluga VHF Contest",
        "КВ Кубок Брянской области" to "Bryansk Oblast HF Cup",
        "КВ Кубок республики Татарстан" to "Republic of Tatarstan HF Cup",
        "КВ Чемпионат ДФО" to "Far Eastern Federal District HF Championship",
        "КВ чемпионат Кемеровской области - Кузбасса" to "Kemerovo Oblast – Kuzbass HF Championship",
        "КВ Чемпионат Приморского края" to "Primorsky Krai HF Championship",
        "КВ Чемпионат РФ - телеграф" to "Russian HF Championship – CW",
        "КВ Чемпионат РФ - телефон" to "Russian HF Championship – SSB",
        "КВ Чемпионат Саратовской области" to "Saratov Oblast HF Championship",
        "КВ Чемпионат СКФО и ЮФО" to "North Caucasus & Southern Federal Districts HF Championship",
        "КВ Чемпионат СКФО и ЮФО Юг России" to "South of Russia HF Championship (North Caucasus & Southern FD)",
        "КВ Чемпионат Смоленской области" to "Smolensk Oblast HF Championship",
        "КВ Чемпионат СРР" to "SRR HF Championship",
        "КВ-кубок Пензенской области" to "Penza Oblast HF Cup",
        "Контест клуба Рыцари неба" to "Knights of the Sky Club Contest",
        "Кубок А.Г. Батурина" to "A.G. Baturin Cup",
        "Кубок Атаман" to "Ataman Cup",
        "Кубок Гагарина" to "Gagarin Cup",
        "Кубок им. А.С. Попова" to "A.S. Popov Cup",
        "Кубок Крыма" to "Crimea Cup",
        "Кубок Москвы по радиосвязи на КВ - телеграф" to "Moscow HF Cup – CW",
        "Кубок памяти UA1DZ" to "UA1DZ Memorial Cup",
        "Кубок первого полета" to "First Flight Cup",
        "Кубок РО ДОСААФ Приморского края" to "DOSAAF Primorsky Krai Cup",
        "Кубок России на КВ - телеграф" to "Russian HF Cup – CW",
        "Кубок России на КВ - телефон" to "Russian HF Cup – SSB",
        "Кубок России по радиосвязи на УКВ" to "Russian VHF Cup",
        "Кубок Саратовской области по радиосвязи на КВ ВОЛЖСКИЕ ДАЛИ" to "Saratov Oblast HF Cup \"Volga Expanses\"",
        "Кубок Тамбовской области по радиосвязи на КВ" to "Tambov Oblast HF Cup",
        "Кубок Урала" to "Ural Cup",
        "Кубок Черноземья" to "Black Earth Region Cup",
        "Мемориал Г.В.Нехорошева" to "G.V. Nekhoroshev Memorial",
        "Мемориал М.С. Крюкова" to "M.S. Kryukov Memorial",
        "Мемориал маршала Г.К.Жукова" to "Marshal G.K. Zhukov Memorial",
        "Мемориал Ю.В.Фогеля" to "Yu.V. Fogel Memorial",
        "Минитест клуба Пятый океан" to "Fifth Ocean Club Mini-test",
        "Минитест Памяти погибших авиаторов" to "Fallen Aviators Memorial Mini-test",
        "Молодежное первенство Белгородской области" to "Belgorod Oblast Youth Championship",
        "Молодежное первенство РФ" to "Russian Youth Championship",
        "Молодёжные соревнования «Снежинка»" to "Youth Contest \"Snowflake\"",
        "Молодежные соревнования Дружба" to "Youth Contest \"Friendship\"",
        "Молодежные соревнования ЗВЕЗДЫ КУБАНИ" to "Youth Contest \"Stars of Kuban\"",
        "Молодежные соревнования Орлята учатся летать." to "Youth Contest \"Eaglets Learn to Fly\"",
        "Молодёжные соревнования Регион" to "Youth Contest \"Region\"",
        "Мороз - Красный Нос" to "Frost the Red Nose",
        "МРС Восточной Сибири и Дальнего Востока" to "East Siberia & Far East Interregional Contest",
        "Огненная дуга" to "Fire Arc",
        "Оренбургский пуховый платок" to "Orenburg Shawl",
        "Открытые соревнования нулевого района России" to "Russian Call Area 0 Open Contest",
        "Открытый КВ Чемпионат Астраханской области" to "Astrakhan Oblast Open HF Championship",
        "Открытый чемпионат Калужской области" to "Kaluga Oblast Open Championship",
        "Открытый чемпионат Костромской области Памяти адмирала Невельского Г.И." to
            "Kostroma Oblast Open Championship – Admiral G.I. Nevelskoy Memorial",
        "Память" to "Memory",
        "Партизанский радист" to "Partisan Radio Operator",
        "Первенство им.Н.Ф.Ватутина" to "N.F. Vatutin Championship",
        "Первенство на кубок КАЗАЧОК" to "Kazachok Cup",
        "Первенство Сибири и Дальнего Востока" to "Siberia & Far East Championship",
        "Первый Салют" to "First Salute",
        "ПОБЕДА - CQ - ДЕТИ" to "Victory – CQ – Children",
        "ПОЁТ МОРЗЯНКА" to "Morse Code Is Singing",
        "Полевой День на УКВ" to "VHF Field Day",
        "Полярный радист" to "Polar Radio Operator",
        "Путь к звездам" to "Path to the Stars",
        "Российский УКВ CW МАРАФОН" to "Russian VHF CW Marathon",
        "Русское Поле - QRP контест-игра" to "Russian Field – QRP contest game",
        "Салют Победы" to "Victory Salute",
        "Самарские молодежные радиосоревнования" to "Samara Youth Radio Contest",
        "Сделай Сам - QRP контест-игра" to "Do It Yourself – QRP contest game",
        "Сделай Сам - QRP контест-игра (Зима)" to "Do It Yourself – QRP contest game (Winter)",
        "Служу Отечеству" to "I Serve the Fatherland",
        "Советский Союз" to "Soviet Union",
        "Соревнования в честь Дня защитника Отечества Честь имею" to "Defender of the Fatherland Day Contest \"I Have the Honour\"",
        "Соревнования клуба Пятый Океан" to "Fifth Ocean Club Contest",
        "Соревнования на кубок 4 Сезона с RCWC" to "RCWC 4 Seasons Cup",
        "Соревнования на кубок RCWC 4 SEASONS" to "RCWC 4 Seasons Cup",
        "Соревнования на кубок RCWC-4-SEASONS" to "RCWC 4 Seasons Cup",
        "Соревнования памяти В.С.Могилёва UA3XAN" to "V.S. Mogilyov UA3XAN Memorial Contest",
        "Соревнования памяти жертв аварий и катастроф на атомных объектах" to "Nuclear Accident Victims Memorial Contest",
        "Соревнования СОЮЗ КЛУБОВ" to "Union of Clubs Contest",
        "Спринт-Мемориал RT3A" to "RT3A Memorial Sprint",
        "Старый Новый Год" to "Old New Year",
        "Сто шагов в небеса" to "A Hundred Steps to the Sky",
        "УКВ кубок РФ имени Ю.А.Гагарина" to "Yu.A. Gagarin Russian VHF Cup",
        "УКВ Кубок Сибири" to "Siberia VHF Cup",
        "УКВ Кубок Урала" to "Ural VHF Cup",
        "УКВ Полевой день Сибири" to "Siberia VHF Field Day",
        "УКВ Чемпионат Кемеровской области" to "Kemerovo Oblast VHF Championship",
        "УКВ Чемпионат Новокузнецка" to "Novokuznetsk VHF Championship",
        "УКВ чемпионат Тамбовской области" to "Tambov Oblast VHF Championship",
        "УКВ-Соревнования «Открытие сезона»" to "VHF Contest \"Season Opening\"",
        "УКВ-соревнования памяти RX1AS" to "RX1AS Memorial VHF Contest",
        "Чемпионат Азиатской части РФ" to "Asian Russia Championship",
        "Чемпионат Белгородской области" to "Belgorod Oblast Championship",
        "Чемпионат Волгоградской области" to "Volgograd Oblast Championship",
        "Чемпионат им. В.И.Мудренко" to "V.I. Mudrenko Championship",
        "Чемпионат Москвы по радиосвязи на КВ" to "Moscow HF Championship",
        "Чемпионат Новокузнецкого радиоклуба" to "Novokuznetsk Radio Club Championship",
        "Чемпионат Пензенской области" to "Penza Oblast Championship",
        "Чемпионат Республики Татарстан" to "Republic of Tatarstan Championship",
        "Чемпионат Тамбовской области CQ R3R" to "Tambov Oblast Championship CQ R3R",
        "Чемпионат Удмуртии" to "Udmurtia Championship",
        "Шестая рота" to "The Sixth Company"
    ).mapKeys { normalize(it.key) }

    internal fun normalize(s: String): String =
        s.lowercase().replace('ё', 'е').replace('–', '-').replace('—', '-')
            .split(Regex("\\s+")).filter { it.isNotEmpty() }
            .joinToString(" ") { word -> if (isCyrillic(word)) word.map { LOOKALIKES[it] ?: it }.joinToString("") else word }
            .trimEnd('.', ' ')

    // ------------------------------------------------------------------ tłumaczenie przybliżone

    private fun word(ru: String) = Regex("(?<!\\p{L})(?:$ru)(?!\\p{L})", RegexOption.IGNORE_CASE)

    private val PHRASES: List<Pair<Regex, String>> = listOf(
        word("по радиосвязи на КВ|на КВ") to "HF",
        word("по радиосвязи на УКВ|на УКВ") to "VHF",
        word("УКВ") to "VHF",
        word("КВ") to "HF",
        Regex("""\((\d+)\s*тур\)""") to "(round $1)",
        word("Всероссийские|Всероссийский") to "All-Russian",
        word("Открытый|Открытые|Открытое") to "Open",
        word("Молодежн\\p{L}*|Молодёжн\\p{L}*") to "Youth",
        word("Чемпионат|Первенство") to "Championship",
        word("Кубок|кубок") to "Cup",
        word("Мемориал|памяти") to "Memorial",
        word("Соревнования|соревнования|Радиосоревнования|Контест") to "Contest",
        word("Минитест") to "Mini-test",
        word("Спринт") to "Sprint",
        word("Марафон") to "Marathon",
        word("радиоклуба") to "Radio Club",
        word("клуба") to "Club",
        word("области") to "Oblast",
        word("края") to "Krai",
        word("республики") to "Republic of",
        word("РФ|России") to "Russia",
        word("телеграф") to "CW",
        word("телефон") to "SSB",
        word("Зима") to "Winter",
        word("Весна") to "Spring",
        word("Лето") to "Summer",
        word("Осень") to "Autumn",
        word("им\\.|имени|на") to "",
        word("и") to "&"
    )

    private val TRANSLIT = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo", 'ж' to "zh",
        'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o",
        'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts",
        'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya"
    )

    internal fun transliterate(s: String): String {
        val sb = StringBuilder()
        for (word in s.split(" ")) {
            if (sb.isNotEmpty()) sb.append(' ')
            val allUpper = word.length > 1 && word.any { it.isLetter() } && word.filter { it.isLetter() }.all { it.isUpperCase() }
            val out = StringBuilder()
            for (ch in word) {
                val t = TRANSLIT[ch.lowercaseChar()]
                when {
                    t == null -> out.append(ch)
                    ch.isUpperCase() -> out.append(t.replaceFirstChar { it.uppercase() })
                    else -> out.append(t)
                }
            }
            sb.append(if (allUpper && isCyrillic(word)) out.toString().uppercase() else out)
        }
        return sb.toString()
    }

    private fun fallback(name: String): String {
        var s = ContestText.dashes(name).replace('ё', 'е').replace('Ё', 'Е')
        for ((re, en) in PHRASES) s = re.replace(s, en)
        s = transliterate(s)
        // Przymiotniki regionów: "Bryanskoy Oblast" -> "Bryansk Oblast", "Primorskogo Krai" -> "Primorsky Krai"
        s = s.replace(Regex("""(\p{L}+)sk(?:oy|aya|iy|ogo|ie|ikh)(?!\p{L})"""), "$1sk")
        s = s.replace(Regex("""(\p{L}+)sk Krai"""), "$1sky Krai")
        return s.replace(Regex("\\s+-\\s+"), " – ").replace(Regex("\\s+"), " ").trim().trim('–', ' ')
    }
}
