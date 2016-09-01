/*
 *  Copyright 2016 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.backend.wasm.render;

import java.util.ArrayList;
import java.util.List;
import org.teavm.backend.wasm.model.WasmFunction;
import org.teavm.backend.wasm.model.WasmLocal;
import org.teavm.backend.wasm.model.WasmMemorySegment;
import org.teavm.backend.wasm.model.WasmModule;
import org.teavm.backend.wasm.model.expression.WasmExpression;
import org.teavm.model.TextLocation;

public class WasmCRenderer {
    private StringBuilder out = new StringBuilder();
    private int indentLevel;
    String currentFile = "";
    int currentLine = -1;
    boolean outputLineNumbers;
    TextLocation lastReportedLocation;

    public boolean isOutputLineNumbers() {
        return outputLineNumbers;
    }

    public void setOutputLineNumbers(boolean outputLineNumbers) {
        this.outputLineNumbers = outputLineNumbers;
    }

    void indent() {
        ++indentLevel;
    }

    void outdent() {
        --indentLevel;
    }

    void line(String line) {
        for (int i = 0; i < indentLevel; ++i) {
            out.append("    ");
        }
        out.append(line).append("\n");
    }

    public void render(WasmModule module) {
        line("#include <inttypes.h>");
        line("#include <string.h>");
        line("#include <stdlib.h>");
        line("#include <assert.h>");
        line("");

        renderFunctionDeclarations(module);
        line("static int8_t *wasm_heap;");
        line("static int32_t wasm_heap_size;");
        renderFunctionTable(module);

        for (WasmFunction function : module.getFunctions().values()) {
            if (function.getImportName() == null) {
                renderFunction(function);
            }
        }

        line("void main() {");
        indent();

        renderHeap(module);
        if (module.getStartFunction() != null) {
            line(module.getStartFunction().getName() + "();");
        }

        for (WasmFunction function : module.getFunctions().values()) {
            if (function.getExportName() != null && function.getExportName().equals("main")) {
                line(function.getName() + "(0);");
            }
        }

        outdent();
        line("}");
    }

    private void renderHeap(WasmModule module) {
        line("wasm_heap_size = " + 65536 * module.getMemorySize() + ";");
        line("wasm_heap = malloc(" + 65536 * module.getMemorySize() + ");");
        for (WasmMemorySegment segment : module.getSegments()) {
            line("memcpy(wasm_heap + " + segment.getOffset() + ",");
            indent();
            for (int i = 0; i < segment.getLength(); i += 48) {
                byte[] data = segment.getData(i, Math.min(i + 48, segment.getLength()) - i);
                boolean last = i + data.length >= segment.getLength();
                StringBuilder sb = new StringBuilder("\"");
                for (int j = 0; j < data.length; ++j) {
                    byte b = data[j];
                    sb.append("\\x")
                            .append(Character.forDigit((b >>> 4) & 0xF, 16))
                            .append(Character.forDigit(b & 0xF, 16));
                }
                sb.append("\"");
                if (last) {
                    sb.append(", " + segment.getLength() + ");");
                }
                line(sb.toString());
            }
            outdent();
        }
    }

    private void renderFunctionTable(WasmModule module) {
        line("static void *wasm_table[] = {");
        if (!module.getFunctionTable().isEmpty()) {
            indent();
            for (int i = 0; i < module.getFunctionTable().size() - 1; ++i) {
                WasmFunction function = module.getFunctionTable().get(i);
                line((function != null ? function.getName() : "unknown") + ",");
            }
            line(module.getFunctionTable().get(module.getFunctionTable().size() - 1).getName());
            outdent();
        }
        line("};");
        line("");
    }

    private void renderFunctionDeclarations(WasmModule module) {
        for (WasmFunction function : module.getFunctions().values()) {
            line(functionDeclaration(function) + ";");
        }
    }

    private void renderFunction(WasmFunction function) {
        WasmCRenderingVisitor visitor = new WasmCRenderingVisitor(function.getResult(),
                function.getLocalVariables().size(), function.getModule());

        StringBuilder declaration = new StringBuilder();
        renderFunctionModifiers(declaration, function);
        declaration.append(WasmCRenderingVisitor.mapType(function.getResult())).append(' ');
        declaration.append(function.getName()).append('(');
        for (int i = 0; i < function.getParameters().size(); ++i) {
            if (i > 0) {
                declaration.append(", ");
            }
            declaration.append(WasmCRenderingVisitor.mapType(function.getParameters().get(i)));
            WasmLocal var = function.getLocalVariables().get(i);
            declaration.append(' ').append(visitor.getVariableName(var));
        }
        declaration.append(") {");
        line(declaration.toString());
        indent();

        List<WasmLocal> variables = function.getLocalVariables().subList(function.getParameters().size(),
                function.getLocalVariables().size());
        for (WasmLocal variable : variables) {
            line(WasmCRenderingVisitor.mapType(variable.getType()) + " " + visitor.getVariableName(variable) + ";");
        }

        List<WasmExpression> body = function.getBody();
        List<CLine> lines = new ArrayList<>();
        if (!body.isEmpty()) {
            for (int i = 0; i < body.size() - 1; ++i) {
                visitor.setRequiredType(null);
                body.get(i).acceptVisitor(visitor);
                lines.addAll(visitor.getValue().getLines());
            }

            visitor.setRequiredType(function.getResult());
            body.get(body.size() - 1).acceptVisitor(visitor);
            lines.addAll(visitor.getValue().getLines());
            if (visitor.getValue().getText() != null) {
                lines.add(new CSingleLine(visitor.getValue().getText()));
            }
        }

        for (CLine line : lines) {
            line.render(this);
        }

        outdent();
        line("}");
        line("");
    }

    private String functionDeclaration(WasmFunction function) {
        StringBuilder sb = new StringBuilder();
        renderFunctionModifiers(sb, function);
        sb.append(WasmCRenderingVisitor.mapType(function.getResult())).append(' ');
        sb.append(function.getName()).append("(");
        for (int i = 0; i < function.getParameters().size(); ++i) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(WasmCRenderingVisitor.mapType(function.getParameters().get(i)));
        }
        sb.append(")");

        return sb.toString();
    }

    private static void renderFunctionModifiers(StringBuilder sb, WasmFunction function) {
        if (function.getImportName() != null) {
            sb.append("extern ");
        } else if (function.getExportName() == null) {
            sb.append("static ");
        }
    }

    @Override
    public String toString() {
        return out.toString();
    }
}
