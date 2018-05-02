package simulator;

import java.util.ArrayList;
import java.util.Hashtable;


public class ToVerilog {
    String xml;        //incoming xml code
    String[] xmlParts;    //xml now split into entries
    int numblocks;    //number of blocks
    ArrayList<Integer>[] inputs;
    int nextOutputPin = 0;    //assign LED pins starting at 0
    int nextInputPin = 0;        //assign switch pins starting at 0

    Hashtable<Integer, Integer> busEquivalency;    //old bus number : source number
    Hashtable<Integer, Integer> busMasks;        //old bus number : bits

    StringBuilder alwaysCode = new StringBuilder();
    StringBuilder alwaysClockCode = new StringBuilder();
    StringBuilder assignmentCode = new StringBuilder();
    StringBuilder commentCode = new StringBuilder();

    ArrayList<Integer> numblocksList, numblocksBitsList, registerblocksBitsList, memoryblocksBitsList, registerInputList, memoryInputList, memoryInputWidthList;

    //the call point for this module
    //pass it a datapath xml it sets up the datastructure
    //then call getC to get the Arduino equivalent code
    public ToVerilog(String xml) {
        this.xml = xml;
        busEquivalency = new Hashtable<Integer, Integer>();
        busMasks = new Hashtable<Integer, Integer>();
        datapathXMLParse();
        numblocks = highestBlockNumber() + 1;
        inputs = new ArrayList[numblocks];
        numblocksList = new ArrayList<Integer>();
        numblocksBitsList = new ArrayList<Integer>();
        registerblocksBitsList = new ArrayList<Integer>();
        memoryblocksBitsList = new ArrayList<Integer>();
        registerInputList = new ArrayList<Integer>();
        memoryInputList = new ArrayList<Integer>();
        memoryInputWidthList = new ArrayList<Integer>();
        for (int i = 0; i < numblocks; i++)
            inputs[i] = new ArrayList<Integer>();
        for (int i = 1; i <= highestBlockNumber(); i++)
            constructBus(i);
        reduceBus();
        for (int i = 1; i <= highestBlockNumber(); i++)
            constructBlock(i);
        clockRegs();
    }

    //eliminate buses from the output
    //all buses are iteratively tracked back to the source
    //all components are then reconnected to their true source
    private void reduceBus() {

        Object[] dests = busEquivalency.keySet().toArray();
        int[] sources = new int[dests.length];
        for (int i = 0; i < dests.length; i++)
            sources[i] = busEquivalency.get(dests[i]);
        //now go through and reassign
        //repeat until converges
        for (int k = 0; k < dests.length; k++) {
            //consider every dest
            for (int i = 0; i < dests.length; i++) {
                int dest = (Integer) dests[i];
                //look at all the entries
                for (int j = 0; j < sources.length; j++) {
                    //if the dest occurs, replace it with its own source
                    if (sources[j] == dest) {
                        sources[j] = sources[i];
                    }
                }
            }
        }
        //now every block dest should link to a block
        busEquivalency.clear();
        for (int blockdest = 0; blockdest < dests.length; blockdest++) {
            busEquivalency.put((Integer) (dests[blockdest]), sources[blockdest]);
//			loopCode.append("table["+(Integer)(dests[blockdest])+"]=table["+sources[blockdest]+"] & "+busMasks.get((Integer)dests[blockdest])+";\n");
        }
    }

    //main external call point
    //generates and returns the C code
    public String getVerilog() {
        StringBuilder v = new StringBuilder();
        v.append(getCopyright());
        v.append(commentCode.toString());
        v.append("module mymodule(input DPSwitch[0:7],Switch[0:5], output LED[0:7]);\n");
        for (int b = 0; b < numblocksList.size(); b++) {
            v.append("reg[0:" + (numblocksBitsList.get(b) - 1) + "] table_" + numblocksList.get(b) + "; ");
        }
        v.append("\n");
        for (int b = 0; b < registerInputList.size(); b++) {
            v.append("reg[0:" + (registerblocksBitsList.get(b) - 1) + "] registerInput_" + registerInputList.get(b) + "; ");
        }
        v.append("\n");
        for (int b = 0; b < memoryInputList.size(); b++) {
            v.append("reg[0:" + (memoryblocksBitsList.get(b) - 1) + "] memory_" + memoryInputList.get(b) + "[0:" + memoryInputWidthList.get(b) + "]; ");
        }
        v.append("\n\n");
        v.append("always begin\n");
        v.append(alwaysCode.toString());
        v.append("end\n");
        v.append("always @(posedge Switch[3]) begin\n");
        v.append(alwaysClockCode.toString());
        v.append("end\n\n");
        v.append(assignmentCode.toString());
        for (int i = nextOutputPin; i < 8; i++)
            v.append("assign LED[" + i + "]=0;\n");
        v.append("endmodule\n");

        return v.toString();
    }

    private String getCopyright() {
        return "// Autogenerated from Emumaker86  Michael Black, 2015\n\n";
    }

    //go through the datapath XML and put together an arraylist of components
    private void datapathXMLParse() {
        ArrayList<String> parts = new ArrayList<String>();
        int c = 0;
        String tag = "";

        //first break up everything by <>
        for (c = 0; c < xml.length(); c++) {
            if (xml.charAt(c) == '<') {
                if (!isWhiteSpace(tag))
                    parts.add(tag);
                tag = "<";
            } else if (xml.charAt(c) == '>') {
                tag += ">";
                parts.add(tag);
                tag = "";
            } else
                tag += xml.charAt(c);
        }

        xmlParts = new String[parts.size()];
        for (int i = 0; i < parts.size(); i++)
            xmlParts[i] = (String) parts.get(i);

    }

    //find the next instance of token in the list
    public int find(String token, int starting) {
        for (int i = starting; i < xmlParts.length; i++) {
            if (xmlParts[i].equals(token))
                return i;
        }
        return -1;
    }

    //true is s only contains whitespace
    private boolean isWhiteSpace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != ' ' && s.charAt(i) != '\t' && s.charAt(i) != '\n')
                return false;
        }
        return true;
    }

    //find where block "number" occurs in the xml, and extract all of its fields into a big array
    private String[] extractBlock(int number) {
        int i, j;
        for (i = 0; i < xmlParts.length; i++) {
            if (xmlParts[i].equals("<number>") && Integer.parseInt(xmlParts[i + 1]) == number)
                break;
        }
        if (i == xmlParts.length)
            return null;
        for (j = i - 1; ; j++) {
            if (xmlParts[j].equals("</" + xmlParts[i - 1].substring(1, xmlParts[i - 1].length())))
                break;
        }
        String[] block = new String[j - i + 2];
        for (int k = i - 1; k <= j; k++)
            block[k - (i - 1)] = xmlParts[k];
        return block;
    }

    //given a token, return its contents
    private String extractField(String[] block, String field) {
        for (int i = 0; i < block.length; i++) {
            if (block[i].equals(field))
                return block[i + 1];
        }
        return null;
    }

    //return the number of the highest-numbered block
    private int highestBlockNumber() {
        int number = 0;
        for (int i = 0; i < xmlParts.length; i++) {
            if (xmlParts[i].equals("<number>") && Integer.parseInt(xmlParts[i + 1]) > number)
                number = Integer.parseInt(xmlParts[i + 1]);
        }
        return number;
    }

    //produce C code to pass registerInputs to table entries on the rising edge of doClock
    private void clockRegs() {
        for (int i = 1; i <= highestBlockNumber(); i++) {
            String[] block = extractBlock(i);
            if (block == null) continue;
            String type = block[0].substring(1, block[0].length() - 1);
            if (type.equals("register")) {
                String a;
                if ((a = extractField(block, "<enable>")) != null)
                    alwaysClockCode.append("if(" + tablestring(Integer.parseInt(a)) + "!=0) ");
                alwaysClockCode.append("table_" + i + "=registerInput_" + i + ";\n");
            } else if (type.equals("register file") || type.equals("memory")) {
                String a = extractField(block, "<address>");
                alwaysClockCode.append("memory_" + i + "[" + tablestring(Integer.parseInt(a)) + "]=registerInput_" + i + ";\n");
                memoryInputList.add(i);
                memoryInputWidthList.add(busMasks.get(Integer.parseInt(a)));
                int bits = Integer.parseInt(extractField(block, "<bits>"));
                memoryblocksBitsList.add(bits);
            }
        }
    }


    //given a bus number, create C code for it, and mark its output blocks
    private void constructBus(int number) {
        //first get its parts
        String[] block = extractBlock(number);
        if (block == null) return;
        String type = block[0].substring(1, block[0].length() - 1);
        if (!type.equals("bus")) return;
        int bits = 0;
        if (extractField(block, "<bits>") != null)
            bits = Integer.parseInt(extractField(block, "<bits>"));
        int mask = (int) (Math.pow(2, bits) - 1);
        //get the bits field
        int entry = 0, exit = 0;

        //get the location coordinates, entry and exit buses
        if (extractField(block, "<entry>") != null) {
            entry = Integer.parseInt(extractField(block, "<entry>"));
        }
        if (extractField(block, "<exit>") != null) {
            exit = Integer.parseInt(extractField(block, "<exit>"));
        }

        //splitter sourced buses are special cases.  we need to keep them in the C code.
        if (entry != 0 && getType(entry).equals("splitter")) {
            System.out.println("splitterbus: " + number + " " + entry);
            //find the splitter line going to this bus
            String fieldtag = "<line " + number + ">";
            System.out.println(fieldtag);
            String field = extractField(extractBlock(entry), fieldtag);
            if (field == null) {
                alwaysCode.append("table_" + number + "=table_" + entry + " & " + mask + ";\n");
                numblocksList.add(number);
                numblocksBitsList.add(bits);
            } else {
                String[] bitrange = field.split(":");
                int high = Integer.parseInt(bitrange[0]);
                int low = Integer.parseInt(bitrange[1]);
                int bitmask = (int) Math.pow(2, high - low + 1) - 1;
                alwaysCode.append("table_" + number + "=(table_" + entry + ">>" + low + ")&" + bitmask + ";\n");
                numblocksList.add(number);
                numblocksBitsList.add(bits);
//busEquivalency.put(number, entry);
            }
        }

//		loopCode.append("table["+number+"]=table["+entry+"] & "+mask+";\n");
        else {
            busEquivalency.put(number, entry);
        }
        busMasks.put(number, mask);
        if (exit != 0 && Integer.parseInt(extractField(block, "<xcoordinate>")) == Integer.parseInt(extractField(block, "<xcoordinate2>"))) {
//			inputs[exit].add(number);
            // add it to block inputs[exit]
            //if the block has other inputs, arrange them from smallest to largest xcoordinate

            int myxcoor = Integer.parseInt(extractField(block, "<xcoordinate>"));
            int x;
            for (x = 0; x < inputs[exit].size(); x++) {
                int b = inputs[exit].get(x);
                int otherxcoor = Integer.parseInt(extractField(extractBlock(b), "<xcoordinate>"));
                if (myxcoor < otherxcoor) break;
            }
            inputs[exit].add(x, number);
        }
    }

    private String tablestring(int number, int i) {
        return tablestring(inputs[number].get(i));
    }

    private String tablestring(int number) {
        //note: buses coming directly from splitters have no equivalency entry
        return "(table_" + busEquivalency.get(number) + "&" + busMasks.get(number) + ") ";
    }

    private String getType(int number) {
        String[] block = extractBlock(number);
        String type = block[0].substring(1, block[0].length() - 1);
        return type;
    }

    //generate C code for each type of component
    private void constructBlock(int number) {
        //first get its parts
        String[] block = extractBlock(number);
        if (block == null) return;
        String type = block[0].substring(1, block[0].length() - 1);
        if (type.equals("bus")) return;
        int bits = 0;
        if (extractField(block, "<bits>") != null)
            bits = Integer.parseInt(extractField(block, "<bits>"));
        String name = "";
        if (extractField(block, "<name>") != null)
            name = extractField(block, "<name>");
        String a = extractField(block, "<address>");

        String v = "";
        if (type.equals("adder") || type.equals("combinational-adder")) {
            v = "table_" + number + "='hffffffff&(";
            for (int i = 0; i < inputs[number].size(); i++) {
                v += tablestring(number, i);
                if (i < inputs[number].size() - 1) v += " + ";
            }
            v += ");\n";
            numblocksList.add(number);
            numblocksBitsList.add(bits);
        } else if (type.equals("joiner")) {
            v = "table_" + number + "=(";
            int b = 0;
            for (int i = inputs[number].size() - 1; i >= 0; i--) {
                v += "(" + tablestring(number, i) + "<<" + b + ")";
                b += (int) (Math.log(busMasks.get(inputs[number].get(i)) + 1) / Math.log(2));
                if (i > 0) v += " | ";
            }
            v += ");\n";
            numblocksList.add(number);
            numblocksBitsList.add(bits);
        } else if (type.equals("combinational-and")) {
            v = "table_" + number + "=(";
            for (int i = 0; i < inputs[number].size(); i++) {
                v += tablestring(number, i);
                if (i < inputs[number].size() - 1) v += " & ";
            }
            v += ");\n";
            numblocksList.add(number);
            numblocksBitsList.add(bits);
        } else if (type.equals("combinational-or")) {
            v = "table_" + number + "=(";
            for (int i = 0; i < inputs[number].size(); i++) {
                v += tablestring(number, i);
                if (i < inputs[number].size() - 1) v += " | ";
            }
            v += ");\n";
            numblocksList.add(number);
            numblocksBitsList.add(bits);
        } else if (type.equals("combinational-nand")) {
            v = "table_" + number + "=~(";
            for (int i = 0; i < inputs[number].size(); i++) {
                v += tablestring(number, i);
                if (i < inputs[number].size() - 1) v += " & ";
            }
            v += ");\n";
            numblocksList.add(number);
            numblocksBitsList.add(bits);
        } else if (type.equals("combinational-nor")) {
            v = "table_" + number + "=~(";
            for (int i = 0; i < inputs[number].size(); i++) {
                v += tablestring(number, i);
                if (i < inputs[number].size() - 1) v += " | ";
            }
            v += ");\n";
            numblocksList.add(number);
            numblocksBitsList.add(bits);
        } else if (type.equals("combinational-xor")) {
            v = "table_" + number + "=(";
            for (int i = 0; i < inputs[number].size(); i++) {
                v += tablestring(number, i);
                if (i < inputs[number].size() - 1) v += " ^ ";
            }
            v += ");\n";
            numblocksList.add(number);
            numblocksBitsList.add(bits);
        } else if (type.equals("combinational-not")) {
            v = "table_" + number + "=~" + tablestring(number, 0) + ";\n";
            numblocksList.add(number);
            numblocksBitsList.add(bits);
        } else if (type.equals("combinational-negate")) {
            v = "table_" + number + "=-" + tablestring(number, 0) + ";\n";
            numblocksList.add(number);
            numblocksBitsList.add(bits);
        } else if (type.equals("combinational-increment")) {
            v = "table_" + number + "=(" + tablestring(number, 0) + "+1)&'hffffffff;\n";
            numblocksList.add(number);
            numblocksBitsList.add(bits);
        } else if (type.equals("combinational-decrement")) {
            v = "table_" + number + "=(" + tablestring(number, 0) + "-1)&'hffffffff;\n";
            numblocksList.add(number);
            numblocksBitsList.add(bits);
        } else if (type.equals("combinational-less-than")) {
            v = "table_" + number + "=" + tablestring(number, 0) + " < " + tablestring(number, 1) + "? 1:0;\n";
            numblocksList.add(number);
            numblocksBitsList.add(bits);
        } else if (type.equals("combinational-equal-to")) {
            v = "table_" + number + "=" + tablestring(number, 0) + " == " + tablestring(number, 1) + "? 1:0;\n";
            numblocksList.add(number);
            numblocksBitsList.add(bits);
        } else if (type.equals("combinational-shift-right")) {
            if (inputs[number].size() == 2)
                v = "table_" + number + "=" + tablestring(number, 0) + " >> " + tablestring(number, 1) + ";\n";
            else
                v = "table_" + number + "=" + tablestring(number, 0) + " >> 1;\n";
            numblocksList.add(number);
            numblocksBitsList.add(bits);
        } else if (type.equals("combinational-shift-left")) {
            if (inputs[number].size() == 2)
                v = "table_" + number + "=" + tablestring(number, 0) + " << " + tablestring(number, 1) + ";\n";
            else
                v = "table_" + number + "=" + tablestring(number, 0) + " << 1;\n";
            numblocksList.add(number);
            numblocksBitsList.add(bits);
        } else if (type.equals("input pin")) {
            commentCode.append("//Switch " + nextInputPin + " is connected to input pin " + name + "\n");
            v = "table_" + number + "=";
            for (int b = 0; b < bits; b++) {
                v += "(DPSwitch[" + nextInputPin + "]<<" + b + ")";
                if (b < bits - 1)
                    v += "|";
                nextInputPin++;
            }
            v += ";\n";
            numblocksList.add(number);
            numblocksBitsList.add(bits);
        } else if (type.equals("output pin")) {
            commentCode.append("//LED " + nextOutputPin + " is connected to output pin " + name + "\n");
            for (int b = 0; b < bits; b++) {
                assignmentCode.append("assign LED[" + nextOutputPin + "]=((" + tablestring(number, 0) + ">>" + b + ")&1)==0? 0:1;\n");
                nextOutputPin++;
            }
        } else if (type.equals("constant")) {
            v = "table_" + number + "='h" + Integer.parseInt(name, 16) + ";\n";
            numblocksList.add(number);
            numblocksBitsList.add(bits);
        } else if ((type.equals("multiplexor") && a != null) || type.equals("data_multiplexor")) {
            v = "";
            for (int i = 0; i < inputs[number].size(); i++) {
                v += "if (" + tablestring(Integer.parseInt(a)) + "==" + i + ") ";
                v += "table_" + number + "=" + tablestring(number, i) + ";\n";
            }
            numblocksList.add(number);
            numblocksBitsList.add(bits);
        } else if (type.equals("register")) {
            v = "registerInput_" + number + "=" + tablestring(number, 0) + ";\n";
            numblocksList.add(number);
            numblocksBitsList.add(bits);
            registerInputList.add(number);
            registerblocksBitsList.add(bits);
        } else if (type.equals("register file")) {
            v = "registerInput_" + number + "=" + tablestring(number, 0) + ";\n";
            v += "table_" + number + "=memory_" + number + "[" + tablestring(Integer.parseInt(a)) + "];\n";
            numblocksList.add(number);
            numblocksBitsList.add(bits);
            registerInputList.add(number);
            registerblocksBitsList.add(bits);
        } else if (type.equals("memory")) {
            v = "registerInput_" + number + "=" + tablestring(number, 0) + ";\n";
            v += "table_" + number + "=memory_" + number + "[" + tablestring(Integer.parseInt(a)) + "];\n";
            numblocksList.add(number);
            numblocksBitsList.add(bits);
            registerInputList.add(number);
            registerblocksBitsList.add(bits);
        }
/*		else if (type.equals("lookup table"))
        {
			headerCode.append("int memory["+number+"][]={");
			for(int i=0; i<busMasks.get(Integer.parseInt(a))+1; i++)
			{
				String val=extractField(block,"<value "+i+">");
				if(val==null)
					headerCode.append("0,");
				else
					headerCode.append("0x"+val+",");
			}
			headerCode.append("};\n");
			v="table["+number+"]=memory["+number+"]["+tablestring(Integer.parseInt(a))+"];\n";
		}
*/
        else if (type.equals("lookup table")) {
            v = "case(" + tablestring(Integer.parseInt(a)) + ") \n";
            for (int i = 0; i < busMasks.get(Integer.parseInt(a)) + 1; i++) {
                String val = extractField(block, "<value " + i + ">");
                if (val != null) {
                    v += "" + i + ": ";
                    v += "table_" + number + " = 'h" + val + "; \n";
                }
            }
            v += "default: table_" + number + "=0; endcase\n";
            numblocksList.add(number);
            numblocksBitsList.add(bits);
        } else if (type.equals("splitter")) {
            v = "table_" + number + "=" + tablestring(number, 0) + ";\n";
            numblocksList.add(number);
            numblocksBitsList.add(bits);
        } else {
            System.out.println("Error: unhandled device " + type);
        }


        alwaysCode.append(v);
    }
}
