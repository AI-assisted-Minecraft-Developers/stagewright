/** The root of the repository this build logic belongs to, whichever of its builds applies it. */
class StageWrightRepo {

    static final File ROOT = load()

    private static File load() {
        def props = new Properties()
        StageWrightRepo.getResourceAsStream('/stagewright-build-logic.properties').withCloseable { props.load(it) }
        new File(props.getProperty('repoRoot'))
    }
}
