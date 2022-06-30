/*******************************************************************************
 * Copyright (c) IBM Corporation
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
 *
 * SPDX-License-Identifier: Apache-2.0
 *******************************************************************************/
package dev.hargrave.strings;

import static aQute.bnd.classfile.ConstantPool.CONSTANT_String;
import static aQute.bnd.exceptions.ConsumerWithException.asConsumer;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import aQute.bnd.classfile.ConstantPool;
import aQute.bnd.osgi.FileResource;
import aQute.bnd.osgi.Jar;
import aQute.bnd.osgi.Resource;
import aQute.lib.io.FileTree;
import aQute.libg.glob.PathSet;
import org.osgi.annotation.bundle.Header;

@Header(name = "Main-Class", value = "${@class}")
public class Strings {

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.err.printf("Strings <file> <includes/!excludes>...%n");
			System.exit(1);
			return;
		}
		File baseFile = new File(args[0]);
		if (!baseFile.exists()) {
			System.err.printf("File %s does not exist%n", baseFile);
			System.exit(1);
			return;
		}
		new Strings(args).run(baseFile);
	}

	private final List<String> includes = new ArrayList<>();
	private final List<String> excludes = new ArrayList<>();
	private final Predicate<String> matches;

	public Strings(String[] args) {
		for (int i = 1; i < args.length; i++) {
			String pattern = args[i];
			if (pattern.startsWith("!")) {
				excludes.add(pattern.substring(1));
			} else {
				includes.add(pattern);
			}
		}
		PathSet paths = new PathSet().includes(includes)
			.excludes(excludes);
		matches = paths.matches("**");
	}

	public void run(File baseFile) throws Exception {
		if (baseFile.isDirectory()) {
			FileTree files = new FileTree();
			files.addIncludes(includes);
			files.addExcludes(excludes);
			files.stream(baseFile, "**")
				.filter(File::isFile)
				.forEachOrdered(asConsumer(this::processFile));
		} else {
			processFile(baseFile);
		}
	}

	void processFile(File file) throws Exception {
		processResource(new FileResource(file));
	}

	void processResource(Resource resource) throws Exception {
		int header = header(resource);
		if (header == 0xCAFEBABE) {
			processClassFile(resource);
		} else if ((header & 0xFFFF0000) == 0x504B0000) {
			processArchive(resource);
		}
	}

	void processResources(Stream<? extends Resource> resources) {
		resources.forEachOrdered(asConsumer(this::processResource));
	}

	void processArchive(Resource resource) throws Exception {
		if (resource instanceof FileResource) {
			try (Jar archive = new Jar(((FileResource) resource).getFile())) {
				processResources(archive.getResources(matches));
			}
		} else {
			try (InputStream in = resource.openInputStream(); Jar archive = new Jar("archive", in)) {
				processResources(archive.getResources(matches));
			}
		}
	}

	void processClassFile(Resource resource) throws Exception {
		try (DataInputStream in = new DataInputStream(resource.openInputStream())) {
			// We avoid full parsing of the class file to save time and I/O
			// and just read the constant pool
			if (in.readInt() != 0xCAFEBABE) {
				throw new IOException("Not a valid class file (no CAFEBABE header)");
			}
			in.readUnsignedShort(); // minor_version
			in.readUnsignedShort(); // major_version
			ConstantPool constant_pool = ConstantPool.read(in);
			in.readUnsignedShort(); // access_flags
			int this_class_index = in.readUnsignedShort();
			String this_class = constant_pool.className(this_class_index);
			System.err.printf(">> CLASS: %s\n", this_class);

			int constant_pool_count = constant_pool.size();
			for (int i = 1; i < constant_pool_count; i++) {
				if (constant_pool.tag(i) == CONSTANT_String) {
					String string = constant_pool.string(i);
					System.out.print(string);
					System.out.print('\n');
				}
			}
		}
	}

	int header(Resource resource) throws Exception {
		if (resource.size() < 4L) {
			return -1;
		}
		try (DataInputStream in = new DataInputStream(resource.openInputStream())) {
			return in.readInt();
		}
	}
}
