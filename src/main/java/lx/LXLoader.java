/*-
 * Copyright 2019 Mariusz Zaborski <oshogbo@FreeBSD.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package lx;

import java.io.IOException;
import java.util.*;

import ghidra.app.util.Option;
import ghidra.app.util.bin.BinaryReader;
import ghidra.app.util.bin.ByteProvider;
import ghidra.app.util.importer.MessageLog;
import ghidra.app.util.opinion.AbstractLibrarySupportLoader;
import ghidra.app.util.opinion.LoadSpec;
import ghidra.program.flatapi.FlatProgramAPI;
import ghidra.program.model.lang.LanguageCompilerSpecPair;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.util.Msg;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

public class LXLoader extends AbstractLibrarySupportLoader {
	@Override
	public String getName() {
		return "Linear eXecutable Module Format";
	}

	@Override
	public Collection<LoadSpec> findSupportedLoadSpecs(ByteProvider provider) throws IOException {
		BinaryReader reader = new BinaryReader(provider, true);
		String signature = reader.readNextAsciiString(2);

		// Standalone LE/LX file
		if ("LE".equals(signature) || "LX".equals(signature)) {
			return List.of(new LoadSpec(this, 0, new LanguageCompilerSpecPair("x86:LE:32:default", "gcc"), true));
		}

		// Embedded LE/LX file
		if ("MZ".equals(signature) && reader.readByte(0x18) >= 0x40) {
			return List.of(new LoadSpec(this, 0, new LanguageCompilerSpecPair("x86:LE:32:default", "gcc"), true));
		}
		
		return List.of();
	}
	
	@Override
	protected void load(ByteProvider provider, LoadSpec loadSpec, List<Option> options,
			Program program, TaskMonitor monitor, MessageLog log)
			throws CancelledException, IOException {
		LX lx;
		long base_addr;
		BinaryReader reader = new BinaryReader(provider, true);
		FlatProgramAPI api = new FlatProgramAPI(program, monitor);
		String signature = reader.readNextAsciiString(2);
		boolean embedded = !("LE".equals(signature) || "LX".equals(signature));

		if (embedded) {
			/* Read address of the real header. */
			reader.setPointerIndex(0x3c);
			base_addr = reader.readNextUnsignedInt();
		} else {
			base_addr = 0;
		}
		
		/* Parse LX/LE. */
		lx = new LX(reader, base_addr);
		
		/* Create segments. */
		for (int hoi = 0; hoi < lx.sizeOfLXObjectTable(); hoi++) {
			LXObjectTable ohdr = lx.getLXObjectTable(hoi);
			String name = ohdr.getName() + Integer.toString(hoi + 1);
			MemoryBlock block;
			byte []data;
			
			if (!ohdr.objectHasPreloadPages()) {
				continue;
			}

			data = lx.readObjectData(reader, ohdr);
			
			try {
				block = api.createMemoryBlock(name, api.toAddr(ohdr.reloc_base_addr), data, false);
				ohdr.setObjectPermissions(block);
			} catch (Exception e) {
				Msg.error(this, e.getMessage());
			}
		}
		
		api.addEntryPoint(api.toAddr(lx.getEIPAddress()));
		api.disassemble(api.toAddr(lx.getEIPAddress()));
		api.createFunction(api.toAddr(lx.getEIPAddress()), "_entry");
	}
}
