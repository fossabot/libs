/*
 * Copyright (c) 2021 Henry 李恒 (henry.box@outlook.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package pub.ihub.process.boot;

import cn.hutool.core.io.file.PathUtil;
import com.google.auto.service.AutoService;
import lombok.Getter;
import net.ltgt.gradle.incap.IncrementalAnnotationProcessor;
import net.ltgt.gradle.incap.IncrementalAnnotationProcessorType;
import pub.ihub.process.BaseJavapoetProcessor;

import javax.annotation.processing.Processor;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static javax.lang.model.SourceVersion.RELEASE_17;
import static javax.tools.StandardLocation.CLASS_OUTPUT;
import static javax.tools.StandardLocation.SOURCE_OUTPUT;
import static pub.ihub.process.boot.SpringFactoriesProcessor.AUTO_CONFIG_POST_PROCESSOR_ANNOTATION;

/**
 * spring.factories配置处理器
 *
 * @author henry
 */
@AutoService(Processor.class)
@SupportedSourceVersion(RELEASE_17)
@SupportedAnnotationTypes({AUTO_CONFIG_POST_PROCESSOR_ANNOTATION})
@IncrementalAnnotationProcessor(IncrementalAnnotationProcessorType.AGGREGATING)
public class SpringFactoriesProcessor extends BaseJavapoetProcessor {

	static final String AUTO_CONFIG_POST_PROCESSOR_ANNOTATION = "pub.ihub.core.AutoConfigPostProcessor";

	protected static final String FACTORIES_RESOURCE = "META-INF/spring.factories";
	protected final Map<String, Set<String>> factories = new HashMap<>();

	@Override
	protected void processElement(Element element) {
		element.getAnnotationMirrors().stream().map(SpringFactoriesAnnotation::findConfigAnnotation)
			.filter(Objects::nonNull).forEach(c -> addFactories(c.configAnnotation, element));
	}

	protected void addFactories(String key, Element element) {
		Set<String> defaultValues = factories.getOrDefault(key, new HashSet<>());
		defaultValues.add(element.getEnclosingElement().toString() + "." + element.getSimpleName());
		factories.put(key, defaultValues);
	}

	@Override
	protected void processingOver() throws IOException {
		String resources = mFiler.getResource(CLASS_OUTPUT, "", FACTORIES_RESOURCE).getName()
			.replace("classes\\java", "resources");
		if (PathUtil.exists(Path.of(resources), true)) {
			note("The %s is exists, ignore generate spring.factories.", resources);
			return;
		}
		// 生成spring.factories
		List<String> lines = new ArrayList<>();
		lines.add("# Generated by ihub-process https://ihub.pub");
		factories.forEach((k, v) -> {
			lines.add(k + "=\\");
			lines.add(String.join(",\\\n", v));
		});
		writeResource(CLASS_OUTPUT, FACTORIES_RESOURCE, lines);
		// annotationProcessor也保存一份便于代码阅读
		writeResource(SOURCE_OUTPUT, FACTORIES_RESOURCE, lines);
	}

	@Getter
	private enum SpringFactoriesAnnotation {

		/**
		 * 环境后处理器
		 */
		ENVIRONMENT_POST_PROCESSOR("org.springframework.boot.env.EnvironmentPostProcessor", AUTO_CONFIG_POST_PROCESSOR_ANNOTATION);

		SpringFactoriesAnnotation(String configAnnotation, String... targetAnnotation) {
			this.configAnnotation = configAnnotation;
			this.targetAnnotation = targetAnnotation;
		}

		private final String configAnnotation;
		private final String[] targetAnnotation;

		static SpringFactoriesAnnotation findConfigAnnotation(AnnotationMirror mirror) {
			return Arrays.stream(values()).filter(a -> Arrays.asList(a.targetAnnotation)
				.contains(mirror.getAnnotationType().toString())).findAny().orElse(null);
		}

	}

}
