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

    // Conctructor for CacheSimulator
    public CacheSimulator(int capacity, int blocksize, int associativity) {
        if (capacity <= 0 || blocksize <= 0 || associativity <= 0) {
            throw new IllegalArgumentException("Cache capacity, block size, and associativity must be positive.");
        }
        this.capacity = capacity;
        this.blocksize = blocksize;
        this.associativity = associativity;
        int numSets = capacity * 1024 / (blocksize * associativity);
        if (numSets <= 0) {
            throw new IllegalArgumentException("Invalid configuration: number of sets must be positive");
        }
        this.cache = new CacheSet[numSets];
        this.mainMemory = new int[MEMORY_SIZE];
        initializeCache();
        initializeMemory();
    }

    private void initializeCache() {
        for (int i = 0; i < cache.length; i++) {
            cache[i] = new CacheSet(this, associativity, blocksize);
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
        
        while ((line = reader.readLine()) != null) {
            String[] tokens = line.split(" ");
            int operation = Integer.parseInt(tokens[0]);
            int address = Integer.parseInt(tokens[1], 16);
            totalLines++;

            if (operation == 0 ) {
                totalRead++;
                read(address);
            }else if (operation == 1) {
                totalWrite++;
                int dataword = Integer.parseInt(tokens[2], 16);
                write(address, dataword);
            }
        }

        reader.close();
        writeBackAllDirtyBlocks();
    }

    private void read(int address) {
        int setIndex = getSetIndex(address);
        int tag = getTag(address);
        CacheSet set = cache[setIndex];

        CacheBlock block = set.getBlockWithTag(tag);

        if (block != null && block.valid) {
            updateLRU(set, block);
        } else {
            readMisses++;
            totalMisses++;
            CacheBlock newBlock = set.evictIfNeeded();
            loadBlockFromMemory(address, newBlock);
            newBlock.tag = tag;
            newBlock.valid = true;
            updateLRU(set, newBlock);
        }
    }

    private void write(int address, int data) {
        int setIndex = getSetIndex(address);
        int tag = getTag(address);
        CacheSet set = cache[setIndex];

        CacheBlock block = set.getBlockWithTag(tag);

        if (block != null && block.valid) {
            block.data[getWordOffset(address)] = data;
            block.dirty = true;
            updateLRU(set, block);
        } else {
            writeMisses++;
            totalMisses++;
            CacheBlock newBlock = set.evictIfNeeded();
            loadBlockFromMemory(address, newBlock);
            newBlock.data[getWordOffset(address)] = data;
            newBlock.dirty = true;
            newBlock.tag = tag;
            newBlock.valid = true;
            updateLRU(set, newBlock);
        }
    }

    private void loadBlockFromMemory(int address, CacheBlock block) {
        int startWord = getWordAddress(address);
        for (int i = 0; i < block.data.length; i++) {
            block.data[i] = mainMemory[startWord + i];
        }
    }

    private void writeBackBlock(CacheBlock block) {
        if (block.dirty) {
            int startAddress = getWordAddressFromTagAndIndex(block.tag, getSetIndex(block.tag));
            for (int i = 0; i < block.data.length; i++) {
                mainMemory[startAddress + i] = block.data[i];
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

    private void updateLRU(CacheSet set, CacheBlock accessedBlock) {
        set.blocks.remove(accessedBlock);
        set.blocks.add(accessedBlock);
    }

    private int getSetIndex(int address) {
        return (address / blocksize) % cache.length;
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

    private int getWordAddressFromTagAndIndex(int tag, int index) {
        return (tag * cache.length + index) * blocksize / 4;
    }

    public void printResults() {
        System.out.printf("STATISTICS:\n");
        System.out.printf("TotalMisses: %d ReadMisses: %d WriteMisses: %d\n", totalMisses, readMisses, writeMisses);
        System.out.printf("TotalMissRate: %.2f%% ReadMissRate: %.2f%% WriteMissRate: %.2f%%\n",
                            (totalMisses * 100.0) / (totalLines),
                            (readMisses * 100.0) / (totalRead),
                            (writeMisses * 100.0) / (totalWrite));
        System.out.printf("Number of Dirty Blocks Evicted from the cache: %d\n\n", dirtyEvictions);
        System.out.print("CACHE CONTENTS\n");
        // more print for cache contents
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
        private LinkedList<CacheBlock> blocks;
        private int associativity;
        private int blockSize;
        private CacheSimulator simulator;
    
        public CacheSet(CacheSimulator simulator, int associativity, int blocksize) {
            this.simulator = simulator;
            this.associativity = associativity;
            this.blockSize = blocksize;
            this.blocks = new LinkedList<>();
    
            for (int i = 0; i < associativity; i++) {
                blocks.add(new CacheBlock(blocksize));
            }
        }

        public CacheBlock getBlockWithTag(int tag) {
            for (CacheBlock block : blocks) {
                if (block.valid && block.tag == tag) {
                    return block;
                }
            }
            return null;
        }

        public CacheBlock evictIfNeeded() {
            if (blocks.size() >= associativity) {
                CacheBlock lruBlock = blocks.removeFirst();
                if (lruBlock.dirty) {
                    simulator.dirtyEvictions++;
                    simulator.writeBackBlock(lruBlock);
                }
                return lruBlock;
            } else {
                CacheBlock newBlock = new CacheBlock(blockSize);
                blocks.add(newBlock);
                return newBlock;
            }
        }
    }
    
    private static class CacheBlock {
        boolean valid;
        boolean dirty;
        int tag;
        int[] data;
    
        public CacheBlock(int blocksize) {
            this.data = new int[blocksize / 4];
            this.valid = false;
            this.dirty = false;
            this.tag = -1 ;
        }
    }

}