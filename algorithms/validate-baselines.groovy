// validate-baselines.groovy
// 用途:在「階段二(lpt/spt)開跑前」一次性驗證所有將用到的 worker 數,其 round-robin 與
//       random-dynamic 兩支基準 log 是否「存在且覆蓋率達標」。任一不過 → 立刻 error 中斷,
//       於是不會啟動任何 lpt/spt pod,避免跑到一半才整批作廢(fail-fast)。
//
// 為何要分別驗兩支(而非聯集):round-robin 自己就會把全部 task 跑一遍,若用聯集覆蓋率,只要
// round-robin 完整,即使 random-dynamic 整支是空檔也會顯示 100% 而漏判,破壞「兩基準對等、各一份
// 樣本」的前提。因此這裡對 round-robin / random-dynamic 各自獨立計算覆蓋率。
//
// GString 陷阱(已驗證):Map.containsKey(GString) 與 Set.contains(GString) 不會命中 String key
// (GString.hashCode != String.hashCode)。log 解析出的 key 是純 String,故 expected key 一律
// 以 .toString() 正規化後再比對,確保覆蓋率計算正確。

def validate(Map config) {
    def taskFile       = config.taskFile
    def workerCounts   = config.workerCounts                 // List<Integer>,通常 = SERIAL_WORKERS
    def minCoveragePct = (config.minCoveragePct ?: 95) as int // 覆蓋率門檻(%),預設 95
    def frameworkPath  = env.WORKSPACE
    def baselineAlgos  = ['round-robin', 'random-dynamic']
    def MAX_CLASSES    = 30

    // --- 1) 從 taskFile 推導預期的 microBatch keys(與四支演算法完全相同的切批邏輯)---
    def rawTasks = readFile(taskFile).split('\n').findAll { it.trim() }
    def expectedKeys = [] as Set
    rawTasks.each { task ->
        def parts = task.split(':', 3)
        if (parts.size() >= 3 && parts[2].trim()) {
            def bug = parts[0]
            def batchId = parts[1]
            def classes = parts[2].split(',')
            if (classes.size() > MAX_CLASSES) {
                for (int i = 0; i < classes.size(); i += MAX_CLASSES) {
                    expectedKeys << "${bug}:${batchId}-sub${(int)(i/MAX_CLASSES)}".toString()   // .toString() 防 GString 陷阱
                }
            } else {
                expectedKeys << "${bug}:${batchId}".toString()
            }
        }
    }
    def total = expectedKeys.size()
    if (total == 0) {
        error("❌ 基準驗證:任務檔 ${taskFile} 解析後得到 0 個 microBatch。請確認 TASK_FILE 路徑與格式(bug:id:class1,class2,...)。")
    }
    echo "🔎 基準驗證開始:預期 ${total} 個 microBatch;待驗 worker 數=${workerCounts};基準=${baselineAlgos};門檻=${minCoveragePct}%"

    // --- 2) 對每個 (worker 數 × 基準) 各自驗「存在性 + 覆蓋率」,蒐集所有問題後一次回報 ---
    def failures = []
    workerCounts.each { wc ->
        baselineAlgos.each { algo ->
            def files = sh(
                script: "ls ${frameworkPath}/experiments/${algo}/batch_durations_*-${wc}w.log 2>/dev/null || true",
                returnStdout: true
            ).split('\n').findAll { it.trim() }

            if (files.size() == 0) {
                failures << "${algo} @ ${wc}w:缺 log 檔(該基準分支可能整支失敗,或階段一未含此 worker 數)"
                return   // 此 (wc, algo) 無檔可算覆蓋率,跳過
            }

            // 聯集所有匹配檔內 duration>0 的 task keys(log 的 key 本身即純 String)
            def loggedKeys = [] as Set
            files.each { f ->
                readFile(f).split('\n').each { line ->
                    def p = line.split(',')
                    if (p.size() >= 2) {
                        try { if (p[1].toDouble() > 0) loggedKeys << p[0] } catch (Exception e) {}
                    }
                }
            }

            def covered = expectedKeys.findAll { loggedKeys.contains(it) }.size()
            // 整數交叉相乘比較,避開浮點/Math 在 Jenkins script-security 沙箱需逐一核可
            if (covered * 100 < total * minCoveragePct) {
                failures << "${algo} @ ${wc}w:覆蓋不足 ${covered}/${total}(log 存在但內容殘缺:空檔 / 部分 worker log 遺失)"
            } else {
                echo "  ✅ ${algo} @ ${wc}w:覆蓋 ${covered}/${total}"
            }
        }
    }

    // --- 3) 有任何問題就一次列出全部並中斷,沒問題才放行階段二 ---
    if (failures.size() > 0) {
        error("❌ 基準驗證未通過,階段二(LPT/SPT)取消執行以免白跑。問題清單:\n  - " +
              failures.join("\n  - ") +
              "\n\n修復方式:重跑階段一(PARALLEL_WORKERS 需含上述 worker 數),確認 round-robin / " +
              "random-dynamic 兩分支皆完整完成(console 無 'WARNING: Failed to collect from worker'),再觸發階段二。")
    }
    echo "✅ 基準驗證全數通過:LPT/SPT 的 EMA 來源完整且對等,進入階段二。"
}

return this
