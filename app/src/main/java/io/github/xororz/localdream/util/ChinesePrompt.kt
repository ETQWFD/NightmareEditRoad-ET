package io.github.xororz.localdream.util

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Offline Chinese -> Stable-Diffusion-prompt helper.
 *
 * SD1.5 text encoders only understand English tags. Sending raw Chinese produces
 * garbage tokens, so when the prompt contains CJK characters we:
 *   1) split on punctuation / spaces,
 *   2) replace known Chinese words/phrases with English tags,
 *   3) append a small quality boost,
 * without any network dependency or added latency.
 *
 * Unknown Chinese words are dropped (they would map to nothing anyway); numbers
 * and latin tokens are kept verbatim. This covers the common vocabulary people
 * actually type (subject / scene / style / quality). It is intentionally a
 * pragmatic tag translator, not a full MT engine.
 */
object ChinesePrompt {

    private val DICT: LinkedHashMap<String, String> = linkedMapOf(
        // quality / boosters
        "最高画质" to "best quality, ultra detailed",
        "高质量" to "high quality, highly detailed",
        "画质精美" to "masterpiece, best quality",
        "杰作" to "masterpiece",
        "高清" to "high resolution, sharp focus",
        "超高清" to "ultra high resolution, 8k",
        "极致细节" to "intricate details, ultra detailed",
        "细节丰富" to "rich details",
        "电影感" to "cinematic, cinematic lighting",
        "电影级" to "cinematic shot, dramatic lighting",
        "光影" to "dramatic light and shadow",
        "写实" to "photorealistic, realistic",
        "真实" to "realistic, lifelike",
        "照片级" to "photorealistic, photo realistic",
        "真实感" to "realistic",
        "精美" to "exquisite, beautiful detailed",
        "唯美" to "aesthetic, beautiful",

        // styles
        "动漫" to "anime style",
        "动画" to "anime style",
        "二次元" to "anime style, 2d",
        "卡通" to "cartoon style",
        "赛博朋克" to "cyberpunk",
        "蒸汽朋克" to "steampunk",
        "古风" to "chinese ancient style",
        "国风" to "chinese style",
        "水墨画" to "chinese ink painting",
        "油画" to "oil painting",
        "水彩" to "watercolor",
        "素描" to "pencil sketch",
        "像素风" to "pixel art",
        "概念艺术" to "concept art",
        "奇幻" to "fantasy art",
        "科幻" to "sci-fi",
        "吉卜力" to "ghibli style",

        // camera / composition
        "特写" to "close-up shot",
        "近景" to "close-up",
        "全景" to "wide shot, panoramic",
        "广角" to "wide angle lens",
        "仰拍" to "low angle shot",
        "俯拍" to "high angle shot, aerial view",
        "鸟瞰" to "bird's eye view",
        "虚化" to "bokeh, depth of field",
        "景深" to "depth of field",
        "逆光" to "backlighting",
        "夜景" to "night scene, night",
        "黄昏" to "sunset, dusk",
        "日出" to "sunrise",
        "蓝天" to "blue sky",
        "白云" to "white clouds",

        // subjects: people
        "女孩" to "1girl",
        "少女" to "1girl, young girl",
        "男孩" to "1boy",
        "少年" to "1boy",
        "女人" to "1woman",
        "男人" to "1man",
        "女性" to "female",
        "男性" to "male",
        "美女" to "beautiful woman, pretty face",
        "帅哥" to "handsome man",
        "小孩" to "child",
        "儿童" to "child",
        "婴儿" to "baby",
        "长发" to "long hair",
        "短发" to "short hair",
        "双马尾" to "twintails",
        "马尾" to "ponytail",
        "白发" to "white hair",
        "金发" to "blonde hair",
        "黑发" to "black hair",
        "蓝发" to "blue hair",
        "红眼" to "red eyes",
        "蓝眼" to "blue eyes",
        "绿眼" to "green eyes",
        "微笑" to "smile",
        "笑容" to "smiling",
        "眼睛" to "detailed eyes",
        "可爱" to "cute, kawaii",
        "萌" to "cute, kawaii",
        "漂亮" to "beautiful",
        "帅气" to "handsome",
        "穿" to "wearing",
        "连衣裙" to "dress",
        "裙子" to "skirt",
        "校服" to "school uniform",
        "制服" to "uniform",
        "西装" to "suit",
        "和服" to "kimono",
        "汉服" to "hanfu",
        "卫衣" to "hoodie",
        "泳衣" to "swimsuit",
        "帽子" to "hat",
        "眼镜" to "glasses",
        "猫耳" to "cat ears",
        "兔耳" to "bunny ears",
        "翅膀" to "wings",
        "天使" to "angel",
        "恶魔" to "demon",
        "精灵" to "elf",
        "公主" to "princess",
        "骑士" to "knight",
        "武士" to "samurai",
        "忍者" to "ninja",
        "机甲" to "mecha",

        // subjects: animals / creatures
        "猫" to "cat",
        "猫咪" to "cat",
        "小猫" to "kitten",
        "狗" to "dog",
        "小狗" to "puppy",
        "鸟" to "bird",
        "鱼" to "fish",
        "马" to "horse",
        "兔子" to "rabbit",
        "狐狸" to "fox",
        "狼" to "wolf",
        "龙" to "dragon",
        "老虎" to "tiger",
        "狮子" to "lion",
        "熊猫" to "panda",
        "蝴蝶" to "butterfly",
        "花" to "flower",
        "樱花" to "cherry blossom",
        "玫瑰" to "rose",
        "树" to "tree",
        "森林" to "forest",
        "山" to "mountain",
        "山脉" to "mountains",
        "湖" to "lake",
        "湖泊" to "lake",
        "河流" to "river",
        "河" to "river",
        "大海" to "sea, ocean",
        "海" to "ocean",
        "海边" to "seaside, beach",
        "海滩" to "beach",
        "沙滩" to "sandy beach",
        "瀑布" to "waterfall",
        "草原" to "grassland, meadow",
        "花田" to "flower field",
        "天空" to "sky",
        "星空" to "starry sky",
        "星星" to "stars",
        "月亮" to "moon",
        "太阳" to "sun",
        "云" to "clouds",
        "雪" to "snow",
        "下雨" to "rain",
        "雨" to "rain",
        "城市" to "city",
        "街道" to "street",
        "建筑" to "building",
        "高楼" to "skyscraper",
        "城堡" to "castle",
        "房子" to "house",
        "房间" to "room",
        "室内" to "indoors",
        "户外" to "outdoors",
        "公园" to "park",
        "花园" to "garden",
        "寺庙" to "temple",
        "桥" to "bridge",
        "宇宙" to "space, universe",
        "星球" to "planet",
        "飞船" to "spaceship",

        // objects / misc
        "汽车" to "car",
        "跑车" to "sports car",
        "摩托车" to "motorcycle",
        "剑" to "sword",
        "刀" to "blade",
        "枪" to "gun",
        "魔法" to "magic",
        "发光" to "glowing",
        "水晶" to "crystal",
        "灯光" to "lights",
        "霓虹灯" to "neon lights",
        "气球" to "balloon",
        "食物" to "food",
        "蛋糕" to "cake",
        "咖啡" to "coffee",
        "书" to "book",

        // colors
        "红色" to "red",
        "蓝色" to "blue",
        "绿色" to "green",
        "黄色" to "yellow",
        "白色" to "white",
        "黑色" to "black",
        "粉色" to "pink",
        "紫色" to "purple",
        "金色" to "golden",
        "银色" to "silver",

        // common verbs / adjectives
        "站在" to "standing",
        "坐在" to "sitting",
        "奔跑" to "running",
        "飞" to "flying",
        "拿着" to "holding",
        "拿着的" to "holding",
        "背景" to "background",
        "一个" to "a",
        "一只" to "a",
        "的" to " ",
        "和" to "and",
        "在" to " ",
        "与" to "and",
    )

    /**
     * 405 扩充层：场景 / 动作 / 服装 / 发型 / 光线 / 天气 / 道具。
     * 这些是用户最常打的整句词，缺一个就会“看起来没按提示语改”。
     */
    private val EXTRA: LinkedHashMap<String, String> = linkedMapOf(
        // 位置与场景
        "厨房" to "kitchen", "客厅" to "living room", "卧室" to "bedroom", "浴室" to "bathroom",
        "教室" to "classroom", "图书馆" to "library", "办公室" to "office", "咖啡厅" to "cafe",
        "咖啡店" to "coffee shop", "便利店" to "convenience store", "超市" to "supermarket",
        "天台" to "rooftop", "走廊" to "hallway", "楼梯" to "staircase", "电梯" to "elevator",
        "地铁" to "subway station", "车站" to "train station", "机场" to "airport",
        "医院" to "hospital", "舞台" to "stage", "废墟" to "ruins", "仓库" to "warehouse",
        "工厂" to "factory", "实验室" to "laboratory", "教堂" to "church", "寺庙" to "temple",
        "海边" to "beach, seaside", "沙滩" to "sandy beach", "游泳池" to "swimming pool",
        "泳池" to "swimming pool", "海滩" to "beach", "湖边" to "lakeside", "河边" to "riverside",
        "瀑布" to "waterfall", "森林" to "forest, woods", "竹林" to "bamboo forest",
        "草原" to "grassland, meadow", "花田" to "flower field", "花海" to "sea of flowers",
        "玫瑰园" to "rose garden", "花园" to "garden", "公园" to "park", "广场" to "plaza",
        "街道" to "street", "巷子" to "alley", "城市" to "cityscape", "都市夜景" to "city night view",
        "雪山" to "snowy mountain", "沙漠" to "desert", "火山" to "volcano", "洞穴" to "cave",
        "稻田" to "rice fields", "麦田" to "wheat field", "樱花树" to "cherry blossom tree",
        "樱花树下" to "under cherry blossom tree", "树下" to "under a tree",
        "屋顶" to "rooftop", "阳台" to "balcony", "窗边" to "by the window", "窗台" to "windowsill",
        "沙发上" to "on the sofa", "床上" to "on the bed", "地板上" to "on the floor",
        "桌子上" to "on the table", "椅子" to "chair", "秋千" to "swing", "天台边缘" to "rooftop edge",
        "赛博城市" to "cyberpunk city", "科幻实验室" to "sci-fi laboratory",
        "中世纪城堡" to "medieval castle", "日式庭院" to "japanese garden",
        "雪景" to "snowscape, winter", "雨夜街道" to "rainy night street",

        // 动作与姿态
        "坐着" to "sitting", "坐在" to "sitting on", "跪着" to "kneeling", "蹲着" to "crouching",
        "躺着" to "lying down", "站着" to "standing", "行走" to "walking", "走路" to "walking",
        "奔跑" to "running", "跳跃" to "jumping", "回头" to "looking back",
        "看向镜头" to "looking at camera", "看着镜头" to "looking at viewer",
        "抬头" to "looking up", "低头" to "looking down", "侧身" to "from side",
        "双手比心" to "heart hands", "比心" to "heart hands", "双手合十" to "praying, hands together",
        "托腮" to "chin on hands", "招手" to "waving", "挥手" to "waving",
        "抱猫" to "holding cat", "抱狗" to "holding dog", "抱着" to "holding",
        "撑伞" to "holding umbrella", "打伞" to "holding umbrella", "拿伞" to "holding umbrella",
        "喝水" to "drinking", "喝奶茶" to "drinking bubble tea", "吃东西" to "eating",
        "吃饭" to "eating", "做饭" to "cooking", "看书" to "reading a book",
        "玩手机" to "looking at phone", "自拍" to "taking a selfie",
        "弹吉他" to "playing guitar", "弹钢琴" to "playing piano", "唱歌" to "singing",
        "跳舞" to "dancing", "画画" to "painting, drawing", "洗澡" to "bathing",
        "伸懒腰" to "stretching", "摸头" to "head pat", "握手" to "handshake",
        "飞吻" to "blowing kiss", "眨眼" to "wink", "微笑" to "smile", "大笑" to "laughing",
        "哭" to "crying, tears", "脸红" to "blush", "惊讶" to "surprised", "生气" to "angry",

        // 服装
        "女仆装" to "maid outfit, maid headdress", "猫耳女仆" to "cat girl, maid outfit, cat ears",
        "水手服" to "sailor outfit", "制服" to "school uniform", "校服" to "school uniform",
        "旗袍" to "china dress, qipao", "和服" to "kimono", "浴衣" to "yukata",
        "婚纱" to "wedding dress", "礼服" to "evening dress", "西装" to "business suit",
        "洛丽塔" to "lolita fashion", "哥特" to "gothic fashion",
        "卫衣" to "hoodie", "毛衣" to "sweater", "白衬衫" to "white shirt",
        "T恤" to "t-shirt", "短袖" to "short sleeves", "外套" to "jacket",
        "风衣" to "trench coat", "羽绒服" to "down jacket", "大衣" to "long coat",
        "连衣裙" to "one-piece dress", "裙子" to "skirt", "百褶裙" to "pleated skirt",
        "短裤" to "shorts", "牛仔裤" to "jeans", "丝袜" to "stockings",
        "过膝袜" to "thighhighs", "白丝" to "white thighhighs", "黑丝" to "black thighhighs",
        "泳装" to "swimsuit", "比基尼" to "bikini", "运动服" to "tracksuit",
        "盔甲" to "armor", "巫师袍" to "wizard robe", "护士服" to "nurse uniform",
        "围裙" to "apron", "披风" to "cape", "斗篷" to "cloak",
        "高跟鞋" to "high heels", "运动鞋" to "sneakers", "靴子" to "boots",
        "拖鞋" to "slippers", "草帽" to "straw hat", "贝雷帽" to "beret",
        "帽子" to "hat", "眼镜" to "glasses", "墨镜" to "sunglasses",
        "耳机" to "headphones", "蝴蝶结" to "ribbon", "发带" to "hair ribbon",
        "猫耳" to "cat ears", "兽耳" to "animal ears", "猫尾" to "cat tail",
        "翅膀" to "angel wings", "光环" to "halo", "手套" to "gloves",
        "项链" to "necklace", "耳环" to "earrings", "铃铛" to "bell",

        // 发型与外貌
        "短发" to "short hair", "长发" to "long hair", "超长发" to "very long hair",
        "双马尾" to "double twintails", "单马尾" to "ponytail", "马尾" to "twintails",
        "卷发" to "curly hair", "直发" to "straight hair", "波浪卷" to "wavy hair",
        "丸子头" to "bun", "盘发" to "updo", "刘海" to "bangs", "呆毛" to "ahoge",
        "粉发" to "pink hair", "蓝发" to "blue hair", "银发" to "grey hair, silver hair",
        "白发" to "white hair", "黑发" to "black hair", "金发" to "blonde hair",
        "紫发" to "purple hair", "绿发" to "green hair", "红发" to "red hair",
        "棕发" to "brown hair", "橙发" to "orange hair",
        "蓝眼" to "blue eyes", "红眼" to "red eyes", "紫眼" to "purple eyes",
        "绿眼" to "green eyes", "金眼" to "yellow eyes", "异瞳" to "heterochromia",
        "尖耳朵" to "elf ears", "精灵" to "elf",

        // 光线 / 天气 / 氛围
        "逆光" to "backlighting", "侧光" to "side lighting", "顶光" to "top lighting",
        "柔光" to "soft lighting", "硬光" to "hard lighting", "霓虹" to "neon lights",
        "烛光" to "candlelight", "灯光" to "lighting", "自然光" to "natural light",
        "晨光" to "morning light", "夕阳" to "sunset", "黄昏" to "dusk, golden hour",
        "蓝色时刻" to "blue hour", "丁达尔光" to "god rays", "轮廓光" to "rim lighting",
        "晴天" to "clear sky", "多云" to "cloudy", "阴天" to "overcast",
        "下雨" to "rain, raining", "雨天" to "rainy day", "雨后" to "after rain",
        "打雷" to "thunderstorm, lightning", "下雪" to "snow, snowing",
        "雾" to "fog", "起雾" to "foggy", "星空" to "starry sky, night sky",
        "夜晚" to "night", "白天" to "daytime", "室内" to "indoors", "户外" to "outdoors",
        "浅景深" to "shallow depth of field", "模糊背景" to "blurred background",

        // 道具
        "伞" to "umbrella", "书" to "book", "手机" to "phone", "相机" to "camera",
        "吉他" to "guitar", "钢琴" to "piano", "电脑" to "computer", "键盘" to "keyboard",
        "咖啡" to "coffee", "茶杯" to "teacup", "奶茶" to "bubble tea",
        "蛋糕" to "cake", "冰淇淋" to "ice cream", "面包" to "bread",
        "花束" to "bouquet", "玫瑰" to "rose", "向日葵" to "sunflower",
        "气球" to "balloon", "猫" to "cat", "狗" to "dog", "兔子" to "rabbit",
        "鸟" to "bird", "鱼" to "fish", "蝴蝶" to "butterfly", "蜡烛" to "candle",
        "礼物" to "present", "行李箱" to "suitcase", "背包" to "backpack",
        "剑" to "sword", "枪" to "gun", "魔法杖" to "staff, magic wand", "盾牌" to "shield",
        "滑板" to "skateboard", "自行车" to "bicycle", "汽车" to "car",
        "摩托车" to "motorcycle", "机器人" to "robot",

        // 句型连接词：把 X 换成 Y / 改成 / 变成 / 加上 / 去掉
        "换成" to "change to", "改成" to "change to", "改为" to "change to",
        "变成" to "becomes", "切换为" to "change to", "替换为" to "replace with",
        "穿上" to "wearing", "戴上" to "wearing", "拿着" to "holding",
        "加上" to "with", "添加" to "with", "去掉" to "without", "删掉" to "without",
        "不要" to "without", "没有" to "without",
        "一只猫" to "a cat", "一把伞" to "an umbrella",
    )

    /** 长词优先替换，避免“厨房”被“房”提前吃掉。 */
    private val ALL_PAIRS: List<Pair<String, String>> by lazy {
        (DICT + EXTRA).entries.sortedByDescending { it.key.length }.map { it.key to it.value }
    }

    /** 词典也覆盖不了的中文词（会给出提示，让用户改用常见说法或直接写英文）。 */
    fun unresolvedWords(input: String?): List<String> {
        val source: String = input?.trim().orEmpty()
        if (source.isEmpty() || !hasChinese(source)) return emptyList()
        var work: String = source
        for ((zh, _) in ALL_PAIRS) {
            if (work.contains(zh)) work = work.replace(zh, " ")
        }
        return work.split(SPLIT)
            .flatMap { it.split(' ') }
            .map { it.trim() }
            .filter { it.isNotEmpty() && hasChinese(it) }
            .distinct()
            .take(6)
    }

    /** 常用英文标签（联想兜底，不依赖外部词库文件）。 */
    private val COMMON_TAGS = listOf(
        "1girl", "1boy", "solo", "smile", "looking at viewer", "upper body", "full body",
        "best quality", "masterpiece", "highly detailed", "sharp focus", "depth of field",
        "white background", "simple background", "outdoors", "indoors", "day", "night",
        "long hair", "short hair", "twintails", "bangs", "blue eyes", "pink hair",
        "school uniform", "maid outfit", "dress", "suit", "kimono", "swimsuit",
        "sitting", "standing", "kneeling", "lying", "running", "walking", "waving",
        "holding", "holding umbrella", "heart hands", "reading a book", "drinking",
        "cherry blossoms", "beach", "forest", "city lights", "cafe", "kitchen", "bedroom",
        "sunset", "bokeh", "cinematic lighting", "soft lighting", "selfie",
    )

    /**
     * 输入联想：按最后一个逗号/空格后的片段给候选（中文词 -> 英文标签）。
     * 只用本地词典，断网可用，也不需要下载词库文件。
     */
    fun suggestions(fragment: String, limit: Int = 10): List<Pair<String, String>> {
        val q = fragment.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val hits = mutableListOf<Pair<String, String>>()
        for ((zh, en) in ALL_PAIRS) {
            if (zh.contains(q) || en.lowercase().contains(q)) hits += zh to en
            if (hits.size >= limit) break
        }
        if (hits.size < limit) {
            for (tag in COMMON_TAGS) {
                if (tag.contains(q) && hits.none { it.second == tag }) hits += tag to tag
                if (hits.size >= limit) break
            }
        }
        return hits
    }

    private val CJK = Regex("[\\u4e00-\\u9fff]")
    // et.45: split ONLY on the user's own punctuation, never on whitespace. Splitting
    // on spaces turned dictionary/MT multi-word phrases like "best quality" /
    // "cat ears" / "Nacho Neko" into comma-separated single words, inflating CLIP
    // token count thousands and wrecking the image. Multi-word English phrases stay
    // whole inside one comma-delimited tag.
    private val SPLIT = Regex("[，,。.；;、！!？?（）()【】\\[\\]\"'“”‘’/\\\\]+")
    private val QUALITY = "masterpiece, best quality"
    private val NEG_HINT = "lowres, bad anatomy, bad hands, text, error, missing fingers, cropped, worst quality, low quality, jpeg artifacts, watermark, signature"

    fun hasChinese(s: String?): Boolean = s != null && CJK.containsMatchIn(s)

    /** Translate a Chinese prompt to an English tag prompt; pass English through. */
    fun translatePrompt(input: String?): String {
        if (input.isNullOrBlank()) return ""
        if (!hasChinese(input)) return input.trim()

        var work: String = input
        // Replace longest phrases first so e.g. 最高画质 wins over 高质量/画质，
        // 且“猫耳女仆”“樱花树下”这类长词不会被短词提前拆掉。
        for ((zh, en) in ALL_PAIRS) {
            if (work.contains(zh)) work = work.replace(zh, " $en ")
        }
        // Tokenize, drop fragments that still contain CJK (unmapped), keep the rest.
        val tags = work.split(SPLIT)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !hasChinese(it) }
            .distinct()
        val joined = tags.joinToString(", ")
        val body = if (joined.isBlank()) QUALITY else "$joined, $QUALITY"
        return body
    }

    /** A Chinese-friendly universal negative prompt (SD1.5 understands English). */
    fun defaultNegative(): String = NEG_HINT

    /* ------------------------------------------------------------------ *
     * Online full-sentence translation.
     *
     * The offline dictionary only maps isolated words and drops any Chinese
     * phrase it does not know, so a complex sentence like
     * "一个穿黑色旗袍的短发女孩站在樱花树下" collapsed into generic tags and the
     * generated picture ignored the user. We therefore first ask a keyless,
     * China-reachable MT endpoint (Youdao aidemo) for a full English sentence,
     * then reshape that sentence into comma-separated SD tags. If the network
     * is unavailable we transparently fall back to the offline dictionary, so
     * generation never blocks on translation.
     *
     * MUST be called off the main thread (the UI already builds generation
     * intents inside Dispatchers.IO).
     * ------------------------------------------------------------------ */

    private const val MT_ENDPOINT = "https://aidemo.youdao.com/trans"
    // Kept short on purpose: this synchronous HTTP call runs on the generation
    // IO dispatcher *before* native inference starts, so on a slow/blocked
    // network an 8s timeout made "generate" appear to freeze (reported as
    // "generation laggy") and a long stall also delayed the safe offline
    // fallback. 4s is still plenty for a tiny JSON POST to a China-reachable
    // endpoint; on failure we fall through to the offline dictionary, which
    // never touches the network. English prompts never call this at all.
    private const val MT_TIMEOUT_MS = 4000

    // After a hard failure (offline / endpoint down) we don't want to pay the 8s
    // timeout on every single generation, but a one-off network blip must not
    // disable full-sentence translation for the whole app process (that silently
    // degraded complex Chinese prompts to the weak offline dictionary, i.e. the
    // "image ignores my prompt" bug). Back off briefly, then retry automatically.
    private const val ONLINE_RETRY_AFTER_MS = 60_000L
    @Volatile private var onlineDisabledUntilMs: Long = 0L

    /**
     * Best-effort Chinese -> SD English tags. English input is returned as-is.
     * Tries online full-sentence MT first (with a short auto-recovering backoff
     * after failures), then the local dictionary as a guaranteed fallback.
     */
    fun translatePromptBest(input: String?): String {
        if (input.isNullOrBlank()) return ""
        if (!hasChinese(input)) return input.trim()
        if (System.currentTimeMillis() >= onlineDisabledUntilMs) {
            // et.183: try the primary endpoint, then a second one before
            // degrading to the tiny offline dictionary. A single blocked
            // endpoint used to leave complex Chinese prompts almost empty.
            val online = translateOnline(input) ?: translateGoogle(input)
            if (!online.isNullOrBlank()) return toTags(online)
        }
        return translatePrompt(input)
    }

    private const val GOOGLE_MT_ENDPOINT = "https://translate.googleapis.com/translate_a/single"

    /** Secondary full-sentence translator; returns null when unavailable. */
    private fun translateGoogle(q: String): String? = try {
        val url = "$GOOGLE_MT_ENDPOINT?client=gtx&sl=zh-CN&tl=en&dt=t&q=" +
            URLEncoder.encode(q, "UTF-8")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = MT_TIMEOUT_MS
            readTimeout = MT_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) NightmareRoad-ET")
        }
        if (conn.responseCode != 200) return null
        val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val segments = org.json.JSONArray(text).optJSONArray(0) ?: return null
        val out = StringBuilder()
        for (i in 0 until segments.length()) {
            val pair = segments.optJSONArray(i) ?: continue
            out.append(pair.optString(0, ""))
        }
        out.toString().trim().ifBlank { null }
    } catch (_: Exception) {
        null
    }

    private fun translateOnline(q: String): String? = try {
        val body = "q=" + URLEncoder.encode(q, "UTF-8") + "&from=zh-CHS&to=en"
        val conn = (URL(MT_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = MT_TIMEOUT_MS
            readTimeout = MT_TIMEOUT_MS
            doOutput = true
            instanceFollowRedirects = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) NightmareRoad-ET")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code != 200) { onlineDisabledUntilMs = backoff(); return null }
        val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONObject(text).optJSONArray("translation")
        if (arr == null || arr.length() == 0) { onlineDisabledUntilMs = backoff(); null }
        else arr.optString(0, "").trim().ifBlank { onlineDisabledUntilMs = backoff(); "" }
    } catch (_: Exception) {
        onlineDisabledUntilMs = backoff()
        null
    }

    private fun backoff(): Long = System.currentTimeMillis() + ONLINE_RETRY_AFTER_MS

    /** Turn a plain English sentence ("A girl in a black dress is standing") into SD tags. */
    private fun toTags(sentence: String): String {
        var s = sentence.lowercase()
            .replace(Regex("[._\\-]"), " ")
            .replace(Regex("[^a-z0-9, ]+"), " ")
        // replace connecting/stop words with commas so the phrase becomes a tag list
        for (w in listOf(
            "there is", "there are", "is standing", "is sitting", "is holding",
            " who ", " which ", " that ", " with ", " wearing ", " inside ",
            " in front of ", " in the ", " in a ", " in ", " on the ", " on a ",
            " under the ", " under ", " over ", " next to ", " near ", " behind ",
            " beside ", " between ", " while ", " when ", " then ", " and ", " the ",
            " this ", " that ", " with a ", " of a ", " of the ", " of ", " is ",
            " are ", " am ", " be ", " being ", " to ", " at ", " by ", " as ",
        )) {
            s = s.replace(w, ", ")
        }
        // et.45: split on COMMAS ONLY, never on spaces. The stop-word replacements
        // above already introduce the commas; splitting on whitespace would shred
        // "best quality" / "cat ears" into single words.
        val tags = s.split(",")
            .map { it.trim() }
            .filter { it.length >= 2 && it !in STOPWORDS }
            .distinct()
        val body = tags.joinToString(", ")
        return if (body.isBlank()) QUALITY else "$body, $QUALITY"
    }

    private val STOPWORDS = setOf(
        "a", "an", "the", "is", "are", "of", "in", "on", "at", "to", "and",
        "with", "by", "as", "be", "she", "he", "it", "they", "her", "his",
        "who", "which", "that", "this", "there", "here", "while", "when",
    )
}
