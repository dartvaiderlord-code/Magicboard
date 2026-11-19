package com.magicboard.keyboard

/** Compact offline prefix + one-edit fuzzy predictor. No typed text is persisted. */
object WordSuggestions {
    private val ru = """
        привет как дела добрый доброе добро пожаловать спасибо пожалуйста здравствуйте пока скоро сегодня завтра вчера
        время когда почему потому если тогда будет были было есть нет да можно нужно надо очень хорошо отлично отлично
        сообщение сообщения написать отправить ответ ответить телефон клавиатура русский английский язык языки перевод
        шифрование расшифровка секрет секретный пароль ключ безопасность данные сообщение чат говорить понятно
        человек люди друзья друг подруга семья мама папа дом работа школа учеба университет машина дорога
        город улица место мир деньги погода тепло холодно солнце дождь снег ветер утро день вечер ночь
        понедельник вторник среда четверг пятница суббота воскресенье неделя месяц год часы минуты
        какой какая какие кто что где куда откуда зачем сколько столько этот эта эти тот та они мы вы ты я он она оно
        мой моя мое твой твоя наш наша ваш ваша свой своя их его ее себя тебе меня нам вам ему ей
        здесь там туда сюда всегда никогда иногда часто редко снова опять уже еще только также тоже
        сейчас потом раньше позже сначала наконец сразу давно недавно быстро медленно больше меньше
        первый второй третий следующий последний новый старый красивый большой маленький высокий низкий
        хороший плохой лучше хуже важный интересный простой сложный главный обычный особенный
        просто конечно возможно наверно наверное точно действительно наверное обязательно желательно
        думаю знаю понимаю вижу слышу хочу могу умею делаю пишу читаю смотрю слушаю иду еду бегу
        скажи скажите расскажи покажи посмотри попробуй помоги помогите сделай сделайте
        пойти прийти выйти зайти найти дать взять сделать сказать написать прочитать посмотреть
        хотеть мочь знать думать видеть слышать любить жить работать учиться понимать помнить забыть
        получить отправить проверить открыть закрыть включить выключить скачать загрузить сохранить
        почему зачем поэтому например значит кажется бывает возможно скорее наверное
        утром днем вечером ночью дома вместе отдельно рядом далеко близко внутри снаружи наверху внизу
        вопрос ответ проблема решение идея план проект программа приложение интернет компьютер
        музыка фильм книга игра видео фото картинка изображение файл папка ссылка сайт страница
        приветик приветствую благодарю извини извините простите согласен согласна готов готова
        завтрака обед ужин еда вода чай кофе хлеб молоко сахар мясо рыба овощи фрукты
        один два три четыре пять шесть семь восемь девять десять сто тысяча миллион
    """.trimIndent().split(Regex("\\s+")).distinct()

    private val en = """
        hello hi hey goodbye thanks thank thankyou please welcome morning evening night today tomorrow yesterday
        yes no maybe sure okay good great fine nice cool awesome amazing perfect really exactly absolutely
        what when where why how who which whose this that these those here there everywhere somewhere nowhere
        i you he she it we they me him her us them my your his its our their mine yours ours theirs
        can could should would will shall may might must need want like love hate know think believe hope
        do does did done doing make made making get got give gave take took find found send sent
        read write wrote written speak spoke say said tell told ask asked answer answered help helped
        see saw seen look watch listen hear heard feel felt come came go went gone leave left
        work working job school university study studying learn learning teach teacher student
        message messages chat conversation reply respond text texted typing keyboard phone mobile android
        encrypt encrypted encryption decrypt decrypted decryption secret password passphrase security private
        friend friends family mother father sister brother girl boy man woman people person
        home house room door window city street road car bus train plane bike trip travel
        morning afternoon evening midnight hour minute second day week month year time date
        monday tuesday wednesday thursday friday saturday sunday january february march april
        good better best bad worse worst big small large little long short high low
        new old young beautiful happy sad angry tired hungry thirsty excited curious
        quick fast slow easy hard simple difficult important interesting possible impossible
        first second third last next previous before after soon later now already again yet
        always never sometimes often usually rarely every each all some none many much few
        yesterday today tomorrow tonight weekend weekday morning tomorrow
        please sorry excuse forgive welcome congratulations wonderful
        computer internet website page link photo image video movie music game book
        water coffee tea milk sugar bread food breakfast lunch dinner apple banana orange
        one two three four five six seven eight nine ten hundred thousand million
        because therefore although however otherwise instead also just even only still
        better beautiful beyond between without within around through above below under over
    """.trimIndent().split(Regex("\\s+")).distinct()

    /** Conservative automatic correction: only known frequent typos, not arbitrary names. */
    fun autoCorrect(word: String, language: String): String? {
        val corrections = if (language == "ru") mapOf(
            "превет" to "привет", "спосибо" to "спасибо", "пожалуста" to "пожалуйста",
            "здраствуйте" to "здравствуйте", "севодня" to "сегодня", "щас" to "сейчас"
        ) else mapOf(
            "teh" to "the", "helo" to "hello", "thnaks" to "thanks",
            "recieve" to "receive", "woudl" to "would", "adn" to "and"
        )
        if (word != word.lowercase()) return null
        return corrections[word]
    }

    fun suggest(prefix: String, language: String): List<String> {
        if (prefix.length < 2 || prefix.length > 24) return emptyList()
        val words = if (language == "ru") ru else en
        val typed = prefix.lowercase()
        val direct = words.filter { it.startsWith(typed) }.take(3)
        if (direct.size >= 3) return direct
        val fuzzy = if (typed.length >= 4) words.filter {
            it !in direct && kotlin.math.abs(it.length - typed.length) <= 1 &&
                it.firstOrNull() == typed.firstOrNull() && distanceAtMostOne(it, typed)
        }.take(3 - direct.size) else emptyList()
        return direct + fuzzy
    }

    private fun distanceAtMostOne(a: String, b: String): Boolean {
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            edits++
            if (edits > 1) return false
            when {
                a.length > b.length -> i++
                b.length > a.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }
}
