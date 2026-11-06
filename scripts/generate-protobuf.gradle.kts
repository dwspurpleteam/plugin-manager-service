import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.getByType

extensions.getByType<SourceSetContainer>().getByName("main").java.srcDir("src/proto-generated-sources/java")
