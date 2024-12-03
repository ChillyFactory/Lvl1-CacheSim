import java.io.*;
import java.util.*;

public class CacheSimulator {
    private int capacity;
    private int blocksize;
    private int associativity;
    private CacheSet[] cache;
    private int[] mainMemory;
    private int totalMisses = 0, readMisses = 0, writeMisses = 0, dirtyEvictions = 0,
        totalLines = 0, totalRead = 0, totalWrite = 0;
    private static final int MEMORY_SIZE = 16 * 1024 * 1024 / 4;
    private static final int[] VALID_CAPACITIES = {4, 8, 16, 32, 64};
    private static final int[] VALID_BLOCKSIZES = {4, 8, 16, 32, 64, 128, 256, 512};
    private static final int[] VALID_ASSOCIATIVITIES = {1, 2, 4, 8, 16};

    // Conctructor for CacheSimulator
    public CacheSimulator(int capacity, int blocksize, int associativity) {
        if (capacity <= 0 || blocksize <= 0 || associativity <= 0) {
            throw new IllegalArgumentException("Cache capacity, block size, and associativity must be positive.");
        }
        if (isValidCapacity(capacity) && isValidBlocksize(blocksize) && isValidAssociativity(associativity)) {
            this.capacity = capacity;
            this.blocksize = blocksize;
            this.associativity = associativity;
        } else {
            throw new IllegalArgumentException("Invalid Capacity/Blocksize/Associativity values.\n"+ 
                "Allowed: -c{4, 8, 16, 32, 64}\t"+
                "-b {4, 8, 16, 32, 64, 128, 256, 512}\t"+
                "-a {1, 2, 4, 8, 16}");
        }

        int numSets = capacity * 1024 / (blocksize * associativity);
        if (numSets <= 0) {
            throw new IllegalArgumentException("Invalid configuration: number of sets must be positive");
        }
        this.cache = new CacheSet[numSets];
        this.mainMemory = new int[MEMORY_SIZE];
        initializeCache();
        initializeMemory();
    }

    private boolean isValidCapacity(int capacity) {
        for (int validCapacity : VALID_CAPACITIES) {
            if (capacity == validCapacity) {
                return true;
            }
        }
        return false;
    }

    private boolean isValidBlocksize(int blocksize) {
        for (int validBlocksize : VALID_BLOCKSIZES) {
            if (blocksize == validBlocksize) {
                return true;
            }
        }
        return false;
    }

    private boolean isValidAssociativity(int associativity) {
        for (int validAssociativity : VALID_ASSOCIATIVITIES) {
            if (associativity == validAssociativity) {
                return true;
            }
        }
        return false;
    }


    private void initializeCache() {
        for (int i = 0; i < cache.length; i++) {
            String hexSetIndex = Integer.toHexString(i); // change set index to hex
            cache[i] = new CacheSet(this, associativity, blocksize, hexSetIndex);

            CacheSet set = cache[i];
            for (int j = 0; j < associativity; j++) {
                CacheBlock block = set.getBlockWithTag(j);
                if (block == null) {
                    block = new CacheBlock(blocksize);
                    set.blocks[j] = block; // Add the new block to the set
                }

                int tag = j;
                int startingAddress = (tag * cache.length + i) * blocksize;

                block.initializeWords(startingAddress);
            }
        }
    }

    private void initializeMemory() {
        for (int i = 0; i < MEMORY_SIZE; i++) {
            mainMemory[i] = i;
        }
    }

    public void processTrace(InputStream traceInput) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(traceInput));
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                String[] tokens = line.split(" ");
                int operation = Integer.parseInt(tokens[0]);
                long address = Long.parseLong(tokens[1], 16);
                totalLines++;
    
                if (operation == 0 ) {
                    totalRead++;
                    read((int) address);
                }else if (operation == 1) {
                    totalWrite++;
                    long dataword = Long.parseLong(tokens[2], 16);
                    write((int) address, (int) dataword);
                }
            }
        } catch (NumberFormatException e) {
            System.err.println("Invalid input line: " + e);
        } catch (Exception e) {
            System.err.println("Excepection Occurred: " + e);
        }


        reader.close();
        //writeBackAllDirtyBlocks();
    }

    private void read(int address) {
        int setIndex = getSetIndex(address);
        int tag = getTag(address);
        CacheSet set = cache[setIndex];

        // Find Block with Tag
        CacheBlock block = null;
        int accessedIndex = -1;
        for (int i = 0; i < set.blocks.length; i++) {
            if (set.blocks[i] != null && set.blocks[i].valid && set.blocks[i].tag == tag) {
                block = set.blocks[i];
                accessedIndex = i;
                break;
            }
        }

        if (block != null) {
            // Cache hit
            updateLRU(set, accessedIndex);
        } else {
            // Cache Miss
            readMisses++;
            totalMisses++;

            // Evict if needed
            int evictIndex = set.evictIfNeeded();
            CacheBlock newBlock = set.blocks[evictIndex];

            // Load the block into the evicted slot
            loadBlockFromMemory(address, newBlock);
            newBlock.tag = tag;
            newBlock.setIndex = getSetIndex(address);
            newBlock.valid = true;

            // Update LRU
            updateLRU(set, evictIndex);
        }
    }

    private void write(int address, int data) {
        int setIndex = getSetIndex(address);
        int tag = getTag(address);
        CacheSet set = cache[setIndex];

        //Find block with tag
        CacheBlock block = null;
        int accessedIndex = -1;
        for (int i = 0; i < set.blocks.length; i++) {
            if (set.blocks[i] != null && set.blocks[i].valid && set.blocks[i].tag == tag) {
                block = set.blocks[i];
                accessedIndex = i;
                break;
            }
        }

        if (block != null && block.valid) {
            // cache hit
            block.words[getWordOffset(address)] = data;
            block.dirty = true;
            updateLRU(set, accessedIndex);
        } else {
            // cache miss
            writeMisses++;
            totalMisses++;

            // Evict if needed
            int evictIndex = set.evictIfNeeded();
            CacheBlock newBlock = set.blocks[evictIndex];

            // Load the block into evicted slot
            loadBlockFromMemory(address, newBlock);
            newBlock.words[getWordOffset(address)] = data;
            newBlock.dirty = true;
            newBlock.setIndex = getSetIndex(address);
            newBlock.tag = tag;
            newBlock.valid = true;

            // Update LRU
            updateLRU(set, evictIndex);

        }
    }

    private void loadBlockFromMemory(int address, CacheBlock block) {
        int startWord = getWordAddress(address);
        for (int i = 0; i < block.words.length; i++) {
            block.words[i] = mainMemory[startWord + i];
        }
    }

    private void writeBackBlock(CacheBlock block) {
        if (block.dirty) {
            int startAddress = getAddressFromTagAndIndex(block.tag, block.setIndex, true);
            for (int i = 0; i < block.words.length; i++) {
                mainMemory[startAddress + i] = block.words[i];
            }
            block.dirty = false;
        }
    }

    private void writeBackAllDirtyBlocks() {
        for (CacheSet set : cache) {
            for (CacheBlock block : set.blocks) {
                if (block.valid && block.dirty) {
                    writeBackBlock(block);
                }
            }
        }
    }

    private void updateLRU(CacheSet set, int accessedIndex) {
        for (int i = 0; i < set.lru.length; i++) {
            if (i == accessedIndex) {
                set.lru[i] = set.associativity - 1;
            } else if (set.lru[i] > 0) {
                set.lru[i]--;
            }
        }
    }

    private int getSetIndex(int address) {
        int setIndex = (address / blocksize) % cache.length;
        /* Test Address and Set
        System.out.println("Address: " + Integer.toHexString(address) + 
                       ", Blocksize: " + blocksize + 
                       ", Cache Length: " + cache.length + 
                       ", Set Index: " + setIndex);
        */
        return setIndex;
    }

    private int getTag(int address) {
        return address / (blocksize * cache.length);
    }

    private int getWordAddress(int address) {
        return address / 4;
    }

    private int getWordOffset(int address) {
        return (address % blocksize) / 4;
    }

    private int getAddressFromTagAndIndex(int tag, int index, Boolean wordAddress) {
        int addressLookUp = (tag * cache.length + index) * blocksize;

        // if it is a wordAddres divide by 4 otherwise return byte address
        if (wordAddress) {
            return addressLookUp / 4;
        }
        else {
            return addressLookUp;
        }
    }

    private void printCacheContents() {
        System.out.print("CACHE CONTENTS\n");
        System.out.printf("%-5s %-5s %-11s %-7s %-8s %-8s %-8s %-8s %-8s %-8s %-8s %-10s%n",
            "Set", "V", "Tag", "Dirty", "Word0", "Word1", "Word2", "Word3", "Word4", "Word5", "Word6", "Word7");

        for (int i = 0; i < cache.length; i++) {
            CacheSet set = cache[i];
            for (CacheBlock block : set.blocks) {
                if (block.valid) {
                    System.out.printf("%-5s %-5d %08x    %-7d",
                        set.getHexString(), block.valid ? 1 : 0, block.tag, block.dirty ? 1 : 0);
                    for (int word : block.words) {
                        System.out.printf(" %08x", word);
                    }
                    System.out.println();
                } else {
                    System.out.printf("%-5s %-5d %-11s %-7s", set.getHexString(), 0, "00000000", "0");
                    for (int j = 0; j < block.words.length; j++) {
                        System.out.printf(" %-8s", "00000000");
                    }
                    System.out.println();
                }
            }
        }
    }

    private void printMainMemory() {
        System.out.println("\nMAIN MEMORY:");
        System.out.printf("%-8s %-8s %-8s %-8s %-8s %-8s %-8s %-8s %-10s%n",
            "Address", "Word0", "Word1", "Word2", "Word3", "Word4", "Word5", "Word6", "Word7");
        
        int startAddress = 0x003f7f00;
        for (int i = startAddress, r = 0; r <= 128; i += 8, r++) {
            System.out.printf("%08x", i);
            for (int j = 0; j < 8; j++) {
                if (i + j < mainMemory.length) {
                    System.out.printf(" %08x", mainMemory[i + j]);
                } else {
                    System.out.printf(" %-10s", "-");
                }
            }
            System.out.println();
        }
    }

    public void printResults() {
        System.out.printf("STATISTICS:\n");
        System.out.print("Misses\n");
        System.out.printf("TotalMisses: %d ReadMisses: %d WriteMisses: %d\n", totalMisses, readMisses, writeMisses);
        System.out.print("Miss rate\n");
        System.out.printf("TotalMissRate: %.2f%% ReadMissRate: %.2f%% WriteMissRate: %.2f%%\n",
                            (totalMisses * 100.0) / (totalLines),
                            (readMisses * 100.0) / (totalRead),
                            (writeMisses * 100.0) / (totalWrite));
        System.out.printf("Number of Dirty Blocks Evicted from the cache: %d\n\n", dirtyEvictions);
        printCacheContents();
        // printMainMemory();
    }

    public static void main(String[] args) {
        try {
            CacheSimulator simulator = new CacheSimulator(Integer.parseInt(args[1]), Integer.parseInt(args[3]), Integer.parseInt(args[5]));
            simulator.processTrace(System.in);
            simulator.printResults();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static class CacheSet {
        private CacheBlock[] blocks;
        private int associativity;
        private int blockSize;
        private CacheSimulator simulator;
        private String hexIndex;
        private int[] lru;
    
        public CacheSet(CacheSimulator simulator, int associativity, int blocksize, String hexIndex) {
            this.simulator = simulator;
            this.associativity = associativity;
            this.blockSize = blocksize;
            this.hexIndex = hexIndex;
            this.blocks = new CacheBlock[associativity];
            this.lru = new int[associativity];

            // Fill lru array with -1 to show empty
            Arrays.fill(lru, -1);

            for (int i = 0; i < associativity; i++) {
                blocks[i] = new CacheBlock(blocksize);
            }
        }

        public String getHexString() {
            return hexIndex;
        }

        public CacheBlock getBlockWithTag(int tag) {
            for (CacheBlock block : blocks) {
                if (block.valid && block.tag == tag) {
                    return block;
                }
            }
            return null;
        }

        public int evictIfNeeded() {
            int evictIndex = -1;
            int minLRU = Integer.MAX_VALUE;

            for (int i = 0; i < lru.length; i++) {
                if (lru[i] < minLRU) {
                    minLRU = lru[i];
                    evictIndex = i;
                }
            }

            CacheBlock evictBlock = blocks[evictIndex];

            // Handle dirty blocks
            if (evictBlock.dirty) {
                simulator.dirtyEvictions++;
                simulator.writeBackBlock(evictBlock);
                evictBlock.dirty = false;
            }

            return evictIndex;
        }

    }
    
    private static class CacheBlock {
        private int[] words; // Holds the data words
        private boolean valid;
        private boolean dirty;
        private int tag;
        private int setIndex;
    
        public CacheBlock(int blocksize) {
            this.words = new int[blocksize / 4];
            this.valid = false;
            this.dirty = false;
            this.setIndex = -1;
            this.tag = -1;
        }

        public void initializeWords(int startingAddress) {
            for (int i = 0; i < words.length; i++) {
                words[i] = startingAddress + i * 4; // Word size in 4 bytes
            }
        }
    }

}