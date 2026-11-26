package com.bettergi.pocket.pathing.settings

import com.bettergi.pocket.pathing.map.MapMatchingMethod
import org.json.JSONObject
import java.text.Collator
import java.util.Locale

/**
 * 安装包内的路线目录。索引每行一条相对 `assets/pathing/routes/` 的路径，
 * 目录层级就是选择界面里的树。
 */
class RouteCatalog(val roots: List<Node>) {
    data class Node(
        val name: String,
        val relativePath: String,
        val file: Boolean,
        val children: List<Node>,
        val searchText: String = "",
        val mapMatchMethod: String? = null,
        val matchMetadataReadable: Boolean = true,
    ) {
        val routePaths: List<String> = if (file) listOf(relativePath) else children.flatMap { it.routePaths }
        val displayName: String get() = arabicDisplayName(if (file) name else name.substringBefore('@'))
        val author: String get() = if (file) "" else name.substringAfter('@', "")

        fun matchingDescription(default: MapMatchingMethod): String {
            if (!matchMetadataReadable) return "تعذر قراءة طريقة المطابقة"
            val method = MapMatchingMethod.resolve(mapMatchMethod, default)
            val source = if (mapMatchMethod.isNullOrBlank()) "حسب الإعدادات" else "محدد في المسار"
            return "${method.label}（$source）"
        }
    }

    private val nodes: Map<String, Node> = buildMap {
        fun visit(children: List<Node>) {
            children.forEach { node ->
                put(node.relativePath, node)
                visit(node.children)
            }
        }
        visit(roots)
    }

    fun node(path: String): Node? = nodes[path]

    /** 按与 resolve 相同的目录顺序压缩队列；完整目录只占一行。 */
    fun queueEntries(selection: Set<String>): List<Node> {
        val selected = selection.toHashSet()
        // 兼容旧的目录选择，同时保持本次遍历与目录节点数成正比。
        selection.forEach { path -> nodes[path]?.takeIf { !it.file }?.let { selected.addAll(it.routePaths) } }
        val counts = HashMap<String, Int>()
        fun count(node: Node): Int {
            val n = if (node.file) if (node.relativePath in selected) 1 else 0 else node.children.sumOf(::count)
            counts[node.relativePath] = n
            return n
        }
        roots.forEach(::count)
        val queue = ArrayList<Node>()
        fun visit(node: Node) {
            val n = counts[node.relativePath] ?: 0
            if (n == 0) return
            if (n == node.routePaths.size) queue += node else node.children.forEach(::visit)
        }
        roots.forEach(::visit)
        return queue
    }

    fun selectionSummary(selection: Set<String>): String {
        if (selection.isEmpty()) return "لم يتم اختيار مسار"
        val queue = queueEntries(selection)
        if (queue.isEmpty()) return "لم يتم اختيار مسار"
        val count = queue.sumOf { it.routePaths.size }
        return "المحدد $count · ${queue.firstOrNull()?.displayName.orEmpty()}" +
            if (queue.size > 1) " وغيرها (${queue.size} مجموعات)" else ""
    }

    /** 以目录为单位浏览；搜索也返回目录，方便一次选中整组路线。 */
    fun browse(directory: String, query: String, limit: Int = SEARCH_LIMIT): Listing {
        val children = if (directory.isEmpty()) roots else nodes[directory]?.children.orEmpty()
        val keywords = query.lowercase(Locale.ROOT).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (keywords.isEmpty()) return Listing(children.map { Row(it, 0, "", false) }, false)
        val out = ArrayList<Row>()
        fun visit(children: List<Node>) {
            for (node in children) {
                if (out.size > limit) return
                val searchable = "${node.relativePath.lowercase(Locale.ROOT)} ${node.searchText} ${node.displayName.lowercase(Locale.ROOT)}"
                if (keywords.all { it in searchable }) {
                    out += Row(node, 0, node.relativePath.substringBeforeLast('/', ""), false)
                } else if (!node.file) {
                    visit(node.children)
                }
            }
        }
        visit(children)
        return Listing(out.take(limit), out.size > limit)
    }

    /** 按目录顺序展开并去重；父目录与其中的文件同时选中也只执行一次。 */
    fun resolve(selection: Set<String>): List<Node> {
        val paths = selection.flatMap { nodes[it]?.routePaths.orEmpty() }.toHashSet()
        return nodes.values.filter { it.file && it.relativePath in paths }
    }

    data class Row(
        val node: Node,
        val depth: Int,
        val breadcrumb: String,
        val expanded: Boolean,
    )

    data class Listing(val rows: List<Row>, val truncated: Boolean)

    /**
     * 没有搜索词时按展开状态输出树。有搜索词时只列出命中的文件，并带上所在目录。
     */
    fun rows(expanded: Set<String>, query: String, limit: Int = SEARCH_LIMIT): Listing {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            val out = ArrayList<Row>()
            fun walk(nodes: List<Node>, depth: Int) {
                for (node in nodes) {
                    val open = !node.file && node.relativePath in expanded
                    out += Row(node, depth, "", open)
                    if (open) walk(node.children, depth + 1)
                }
            }
            walk(roots, 0)
            return Listing(out, false)
        }
        val out = ArrayList<Row>()
        fun walk(nodes: List<Node>) {
            for (node in nodes) {
                if (out.size > limit) return
                if (node.file) {
                    if (node.name.contains(keyword, ignoreCase = true) ||
                        node.relativePath.contains(keyword, ignoreCase = true)
                    ) {
                        val crumb = node.relativePath.substringBeforeLast('/', "")
                        out += Row(node, 0, crumb, false)
                    }
                } else {
                    walk(node.children)
                }
            }
        }
        walk(roots)
        return Listing(out.take(limit), out.size > limit)
    }

    companion object {
        private val arabicNames = mapOf(
            "地方特产" to "الموارد المحلية",
            "稻妻" to "إينازوما",
            "枫丹" to "فونتين",
            "璃月" to "ليويه",
            "蒙德" to "موندشتات",
            "纳塔" to "ناتلان",
            "挪德卡莱" to "نود كراي",
            "须弥" to "سوميرو",
            "至冬" to "سنيجنايا",
            "清心" to "تشينغشين",
            "琉璃袋" to "فيوليت غراس",
            "霓裳花" to "زهرة الحرير",
            "琉璃百合" to "زنبق غليز",
            "夜泊石" to "نوتيلوكوس",
            "石珀" to "كور لابيس",
            "绝云椒椒" to "فلفل جويون",
            "星螺" to "صدفة النجمة",
            "落落莓" to "فال بيري",
            "塞西莉亚花" to "سيسيليا",
            "风车菊" to "أستر عجلة الرياح",
            "嘟嘟莲" to "زنبق كالا",
            "钩钩果" to "وولف هوك",
            "慕风蘑菇" to "فطر فيلانيمو",
            "小灯草" to "عشبة المصباح",
            "蒲公英籽" to "بذور الهندباء",
            "鸣草" to "عشبة ناكو",
            "绯樱绣球" to "زهرة ساكورا",
            "鬼兜虫" to "أونيكابوتو",
            "血斛" to "ديندروبيوم",
            "晶化骨髓" to "نخاع بلوري",
            "海灵芝" to "غانوديرما البحر",
            "珊瑚真珠" to "لؤلؤ سانغو",
            "天云草实" to "فاكهة أماكومو",
            "幽灯蕈" to "فطر فلورسنت",
            "月莲" to "لوتس نيلوتبال",
            "劫波莲" to "لوتس كالبالاتا",
            "树王圣体菇" to "فطر روكاشافا",
            "帕蒂沙兰" to "باديسارا",
            "赤念果" to "ريدكريست",
            "圣金虫" to "الجعران",
            "沙脂蛹" to "شرنقة دهن الرمل",
            "悼灵花" to "زهرة الحداد",
            "万相石" to "تريشيرايت",
            "柔灯铃" to "لوميدوس بيل",
            "虹彩蔷薇" to "وردة قوس قزح",
            "海露花" to "روماريتايم",
            "苍晶螺" to "بيريل كونش",
            "幽光星星" to "لوميتوائل",
            "子探测单元" to "وحدة الكشف الفرعية",
            "湖光铃兰" to "زنبق لايت ليك",
            "初露之源" to "ينبوع الندى الأول",
            "清水玉" to "يشم المياه الصافية"
        )
        fun arabicDisplayName(value: String): String = arabicNames.entries.fold(value) { result, (source, target) -> result.replace(source, target) }

        const val INDEX_ASSET = "pathing/routes-index.txt"
        const val METADATA_ASSET = "pathing/routes-metadata.json"
        const val ROOT_ASSET = "pathing/routes"
        const val DEFAULT_DIRECTORY = "地方特产"
        const val SEARCH_LIMIT = 80

        private val nameOrder: Collator = Collator.getInstance(Locale.CHINA)

        fun parse(text: String, metadataJson: String? = null, searchKey: (String) -> String = { "" }): RouteCatalog {
            val root = Dir("", "")
            val metadata = metadataJson?.let(::JSONObject)
            val seen = HashSet<String>()
            for (raw in text.lineSequence()) {
                val path = raw.trim().removePrefix("\uFEFF").replace('\\', '/')
                if (path.isEmpty() || path.startsWith("#")) continue
                if (path.startsWith('/') || path.split('/').any { it == ".." || it == "." }) continue
                if (!path.endsWith(".json", ignoreCase = true)) continue
                if (!seen.add(path)) continue
                val parts = path.split('/').filter { it.isNotEmpty() }
                if (parts.isEmpty()) continue
                var dir = root
                val acc = ArrayList<String>(parts.size - 1)
                for (i in 0 until parts.lastIndex) {
                    acc += parts[i]
                    val rel = acc.joinToString("/")
                    dir = dir.dirs.getOrPut(parts[i]) { Dir(parts[i], rel) }
                }
                val fileName = parts.last()
                val info = metadata?.optJSONObject(path)
                dir.files += Node(
                    stem(fileName), path, true, emptyList(), searchKey(path),
                    mapMatchMethod = info?.opt("map_match_method") as? String,
                    matchMetadataReadable = metadata == null || info != null,
                )
            }
            return RouteCatalog(root.toChildren(searchKey))
        }

        private fun stem(fileName: String): String =
            if (fileName.endsWith(".json", ignoreCase = true)) fileName.dropLast(5) else fileName

        private class Dir(val name: String, val relativePath: String) {
            val dirs = LinkedHashMap<String, Dir>()
            val files = ArrayList<Node>()

            fun toChildren(searchKey: (String) -> String): List<Node> {
                val folders = dirs.values.map { Node(it.name, it.relativePath, false, it.toChildren(searchKey), searchKey(it.relativePath)) }
                return (folders + files).sortedWith { a, b ->
                    val group = a.file.compareTo(b.file)
                    if (group != 0) group else nameOrder.compare(a.name, b.name)
                }
            }
        }
    }
}
