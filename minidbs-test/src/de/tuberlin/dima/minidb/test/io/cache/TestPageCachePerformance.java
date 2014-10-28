package de.tuberlin.dima.minidb.test.io.cache;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.MethodRule;

import com.carrotsearch.junitbenchmarks.BenchmarkOptions;
import com.carrotsearch.junitbenchmarks.BenchmarkRule;

import de.tuberlin.dima.minidb.api.AbstractExtensionFactory;
import de.tuberlin.dima.minidb.catalogue.ColumnSchema;
import de.tuberlin.dima.minidb.catalogue.TableSchema;
import de.tuberlin.dima.minidb.core.DataType;
import de.tuberlin.dima.minidb.core.IntField;
import de.tuberlin.dima.minidb.io.cache.CachePinnedException;
import de.tuberlin.dima.minidb.io.cache.CacheableData;
import de.tuberlin.dima.minidb.io.cache.DuplicateCacheEntryException;
import de.tuberlin.dima.minidb.io.cache.EvictedCacheEntry;
import de.tuberlin.dima.minidb.io.cache.PageCache;
import de.tuberlin.dima.minidb.io.cache.PageSize;


/**
 * The public test case for the cache.
 * 
 * @author Stephan Ewen (sewen@cs.tu-berlin.de)
 * @author Michael Saecker (modified)
 */
public class TestPageCachePerformance
{
	/**
	 * Fixed seed to make tests reproducible.
	 */
	private static final long SEED = 347987612876524L;
	
	/** 
	 * Size of the pages in the performance test.
	 * Use smallest page size to use as few memory as possible.
	 */
	private final static PageSize pz = PageSize.values()[0]; 
	
	/**
	 * Size of the cache in the performance test.
	 */
	private final static int size = 100000; 
	
	/**
	 * 	Number of cache accesses in the benchmark.
	 */
	private final static int cAccesses = size * 50;
	
	/**
	 * Percentage of pages that have high hits.
	 */
	private final static float highHitsPercentage = 0.1f;
	
	// ratios for the performance test (sum should be 1.0f)
	private static float getRatio = 0.3f; 
	private static float insertRatio = 0.6f;
	@SuppressWarnings("unused")
	private final static float unpinRatio = 0.1f; // not referenced but present for comprehension

	/**
	 * Ratio for pinning pages in insert requests.
	 */
	private static float pinRatioInsert = 0.05f;
	/**
	 * Ratio for pinning pages in get requests.
	 */
	private static float pinRatioGet = 0.05f;

	/**
	 * Decides at which points the behavior 
	 * should swap between scan-driven and request-driven.
	 */
	private final static float swapGetInsertRatio = 0.2f;
	
	/** 
	 * Ratio for trying to retrieve uncontained pages from the cache.
	 */
	private final static float getUncontainedRatio = 0.1f;
	/**
	 * Ratio how many of the gets will be of the high hits list.
	 */
	private final static float highHitsRatio = 0.6f;
	/**
	 * Ratio of how many inserts will a replace an element in the high hits list.
	 */
	private final static float replaceHighHitsRatio = 0.2f;
	
	/**
	 * Sum of ratios for workflow of performance test.
	 * DO NOT MODIFY!
	 */
	private static final float getRatioSum = insertRatio + getRatio;
	
	/**
	 * Mark test as benchmark.
	 */
	@Rule
	public MethodRule benchmarkRun = new BenchmarkRule();
	
	/**
	 * The random number generator used to create elements.
	 */
	private Random random = new Random(SEED);
	
	/**
	 * The page cache to test.
	 */
	private PageCache underTest;

	/**
	 * The schema used for the test.
	 */
	private TableSchema schema;
	
	/**
	 * A list containing all entry ids that are currently stored in the cache.
	 */
	private ArrayList<EntryId> contained;
	
	/**
	 * Map that stores entry ids and the index of the entry in the contained list.
	 */
	private HashMap<EntryId, Integer> containedIndex;
	
	/**
	 * List that contains all entry ids that are marked for frequent requests. 
	 */
	private ArrayList<EntryId> highHits;	
	
	/**
	 * Set containing entry ids of the pinned pages.
	 */
	private HashSet<EntryId> pinned;
	
	
	
	/**
	 * Writes a few infos about the benchmark to the console.
	 */
	@BeforeClass
	public static void benchmarkInfos()
	{
		System.out.println("");
		System.out.println("Page size in B: " + pz.getNumberOfBytes());
		System.out.println("Cache size: " + size);
		System.out.println("Number of cache accesses: " + cAccesses);
		System.out.println("Number of pages with high hits: " + (size * highHitsPercentage));
		System.out.println("");
	}
	
	/**
	 * Sets up a warm cache (all elements hit only once).
	 * Also tests the addPage() behavior for cold caches.
	 * 
	 * @see junit.framework.TestCase#setUp()
	 */
	@Before
	public void setUp() throws Exception
	{
		// load the custom code
		AbstractExtensionFactory.initializeDefault();
		
		// even number size [500 .. 2000]
		// create a page size, but exclude the last one (consumes too much memory)
		// to avoid OutOfMemoryErrors. Note: With higher -xmx parameter that would work
		this.schema = initSchema(pz);
		
		// create the test instance
		this.underTest = AbstractExtensionFactory.getExtensionFactory().createPageCache(pz, size);
		this.contained = new ArrayList<EntryId>(size);
		this.containedIndex = new HashMap<EntryId, Integer>();
		this.pinned = new HashSet<EntryId>();
		this.highHits = new ArrayList<EntryId>((int) Math.ceil((size) * highHitsPercentage));	
		
		byte[] buffer = new byte[pz.getNumberOfBytes()];

		// fill empty cache with data and check that it behaves properly as a cold cache does
        while (this.contained.size() < size) {
			
			// generate the next entry
            int resourceId = this.random.nextInt(Integer.MAX_VALUE);
			CacheableData next = null;
			EntryId eid = null;
			do {
				next = generateRandomCacheEntry(resourceId, buffer);
				eid = new EntryId(resourceId, next.getPageNumber());
			}
			while (this.containedIndex.containsKey(eid));
			this.containedIndex.put(eid, this.contained.size());
			this.contained.add(this.contained.size(), eid);
			
			if(this.random.nextFloat() <= highHitsPercentage)
			{
				this.highHits.add(eid);
			}
			
			// add the entry and make sure we always get the empty entries back and no
			// others
			try {
				EvictedCacheEntry evicted = this.underTest.addPage(next, eid.getResourceId());
				assertTrue("addPage() method must not return null.", evicted != null);
				assertTrue("Initial entries must contain a buffer", evicted.getBinaryPage() != null);
				assertTrue("Initial entries must have no resource data.", evicted.getWrappingPage() == null);
				// disable prefetching behavior (request page once)
				this.underTest.getPage(eid.getResourceId(), eid.getPageNumber());
				
				// recycle the buffer
				buffer = evicted.getBinaryPage();
				assertTrue(buffer != null);
			}
			catch (DuplicateCacheEntryException sceex) {
				fail("No duplicate entry occurred here, no exception must be thrown.");
			}
			catch (CachePinnedException cpex) {
				fail("Cache is not pinned at this point.");
			}
		}
	}
	
	/**
	 * Cleanup after a test case.
	 */
	@After
    public void tearDown() throws Exception
    {
	    this.underTest = null;
	    this.contained.clear();
	    System.gc();
    }

	/**
	 * Tests the performance of the cache.
	 */
	@BenchmarkOptions(benchmarkRounds = 10, warmupRounds = 5)
	@Test
	public void testLowPinRatio() throws Exception {
		pinRatioInsert = 0.05f;
		pinRatioGet = 0.05f;
		// run test
		testPerformance();
	}

	@BenchmarkOptions(benchmarkRounds = 10, warmupRounds = 5)
	@Test
	public void testMediumPinRatio() throws Exception {
		pinRatioInsert = 0.25f;
		pinRatioGet = 0.25f;
		// run test
		testPerformance();
	}
	
	@BenchmarkOptions(benchmarkRounds = 10, warmupRounds = 5)
	@Test
	public void testHighPinRatio() throws Exception {
		pinRatioInsert = 0.40f;
		pinRatioGet = 0.40f;
		// run test
		testPerformance();
	}
	
	/**
	 * Executes a single performance measurement run.
	 */
	public void testPerformance() 
		throws Exception
	{
		int lastSwap = 0;
		byte[] buffer = new byte[pz.getNumberOfBytes()];
		for (int i = 0; i < cAccesses; i++)
		{
			// swap behavior?
			if (Math.floor(i / cAccesses * swapGetInsertRatio) > lastSwap)
			{
				lastSwap++;
				// swap get and insert ratio
				float iRatio = insertRatio;
				insertRatio = getRatio;
				getRatio = iRatio;
			}
			
			// switch cache action
			float action = this.random.nextFloat();
			if (action <= insertRatio)
			{
				// add new page
				// generate the next entry
	            int resourceId = this.random.nextInt(Integer.MAX_VALUE);
				CacheableData next = null;
				EntryId eid = null;
				do {
					next = generateRandomCacheEntry(resourceId, buffer);
					eid = new EntryId(resourceId, next.getPageNumber());
				}
				while (this.containedIndex.containsKey(eid));
				
				EvictedCacheEntry evicted;
				// randomly check if page should be pinned
				// at most pin half of the cache
				if (this.random.nextFloat() <= pinRatioInsert && this.pinned.size() < size/2)
				{
					// pin page
					evicted = this.underTest.addPageAndPin(next, eid.getResourceId());
					this.pinned.add(eid);
				}
				else
				{
					evicted = this.underTest.addPage(next, eid.getResourceId());					
					
				}
				// swap one out of three elements with highly hit elements
				if (this.random.nextFloat() <= replaceHighHitsRatio)
				{
					this.highHits.set(this.random.nextInt(this.highHits.size()), eid);
				}
				// disable prefetching behavior (request page once)
				this.underTest.getPage(eid.getResourceId(), eid.getPageNumber());
				// remove evicted page from contained set
				EntryId oldEntry = new EntryId(evicted.getResourceID(),evicted.getPageNumber());
				Integer pos = this.containedIndex.get(oldEntry);
				this.containedIndex.remove(oldEntry);
				this.containedIndex.put(eid, pos);
				this.contained.set(pos, eid);

				// recycle the buffer
				buffer = evicted.getBinaryPage();
			}
			else if (action <= getRatioSum)
			{
				// get page
				// randomly select contained or uncontained page
				if (this.random.nextFloat() <= getUncontainedRatio)
				{
					// request uncontained page
					EntryId eid = null;
					do
					{
						eid = generateRandomEntryId();
					}
					while (this.containedIndex.containsKey(eid));
					this.underTest.getPage(eid.getResourceId(), eid.getPageNumber());
				}
				else
				{
					if (this.random.nextFloat() <= highHitsRatio)
					{
						// randomly select highly hitted page
						EntryId eid = this.highHits.get(this.random.nextInt(this.highHits.size()));
						this.underTest.getPage(eid.getResourceId(), eid.getPageNumber());
					}
					else
					{
						// request contained page
						// pick a random page
						
						EntryId eid = this.contained.get(this.random.nextInt(this.contained.size()));
						// randomly check if page should be pinned
						// at most pin half of the cache
						if (this.random.nextFloat() <= pinRatioGet && this.pinned.size() < size/2)
						{
							// randomly pin
							this.underTest.getPageAndPin(eid.getResourceId(), eid.getPageNumber());
							this.pinned.add(eid);
						}
						else
						{
							// simply request page
							this.underTest.getPage(eid.getResourceId(), eid.getPageNumber());						
						}
					}
				}
			}
			else
			{
				// unpin page
				if (this.pinned.size() > 0)
				{
					EntryId unpin = this.pinned.iterator().next();
					this.pinned.remove(unpin);
					this.underTest.unpinPage(unpin.getResourceId(), unpin.getPageNumber());
				}
				else
				{
					// unpin any page for the cache access
					EntryId eid = generateRandomEntryId();
					this.underTest.unpinPage(eid.getResourceId(), eid.getPageNumber());
				}
			}
		}
	}
	

	/*
	 * ********************************************************************************************
	 * 
	 *                                       U t i l i t i e s
	 *                    
	 * ********************************************************************************************
	 */
	
	/**
	 * Generates a random entry id. (Helper class for test case).
	 */
	private EntryId generateRandomEntryId()
	{
		int id = this.random.nextInt(Integer.MAX_VALUE);
		int num = this.random.nextInt(Integer.MAX_VALUE);
		
		return new EntryId(id, num);
	}

	
	/**
	 * Generates a random cache entry.
	 * 
	 * @param id The resource id of the page.
	 * @param buffer The buffer to use for the page.
	 * @return The cache entry.
	 * @throws Exception
	 */
	private CacheableData generateRandomCacheEntry(int id, byte[] buffer) throws Exception
	{		
		int num = this.random.nextInt(Integer.MAX_VALUE);
		
		// generate a dummy page around it
		CacheableData cd = AbstractExtensionFactory.getExtensionFactory().initTablePage(this.schema, buffer, num);
		
		// mark the data buffer
		IntField.encodeIntAsBinary(new EntryId(id, num).hashCode(), buffer, this.schema.getPageSize().getNumberOfBytes() - 4);
		
		return cd;
	}
	
	/**
	 * Initializes a schema for the given page size.
	 * 
	 * @param pageSize The page size.
	 * @return The schema.
	 */
	private TableSchema initSchema(PageSize pageSize)
	{
		TableSchema schema = new TableSchema(pageSize);
		
		// generate a random set of columns
		int numCols = this.random.nextInt(20) + 1;
		
		// create the columns as given
		for (int col = 0; col < numCols; col++) {
			DataType type = getRandomDataType();
			schema.addColumn(ColumnSchema.createColumnSchema("Random Column " + col, type, true));
		}
		
		return schema;
	}
	
	
	/**
	 * Generates a random data type for the schema.
	 * 
	 * @return A random data type.
	 */
	private DataType getRandomDataType()
	{
		int num = this.random.nextInt(10);
		
		switch (num) {
		case 0:
			return DataType.smallIntType();
		case 1:
			return DataType.intType();
		case 2:
			return DataType.bigIntType();
		case 3:
			return DataType.floatType();
		case 4:
			return DataType.doubleType();
		case 5:
			return DataType.charType(this.random.nextInt(256) + 1);
		case 6:
			return DataType.varcharType(this.random.nextInt(256) + 1);
		case 7:
			return DataType.dateType();
		case 8:
			return DataType.timeType();
		case 9:
			return DataType.timestampType();
		default:
			return DataType.intType();	
		}
	}	

	
	
	/**
	 * Helper class to track resource ids and page numbers of cache entries.
	 */
	private class EntryId
	{
		private int id;
		private int pagenumber;
		

		/**
		 * Ctr.
		 * 
		 * @param id The resource id.
		 * @param pagenumber The page number.
		 */
		public EntryId(int id, int pagenumber)
        {
	        this.id = id;
	        this.pagenumber = pagenumber;
        }


		/**
		 * Returns the page number of this entry id.
		 * 
		 * @return The page number.
		 */
		public int getPageNumber() 
		{
			return this.pagenumber;
		}


		/**
		 * Returns the resource id of this entry id.
		 * 
		 * @return The resource id.
		 */
		public int getResourceId() 
		{
			return this.id;
		}


		/*
		 * (non-Javadoc)
		 * @see java.lang.Object#hashCode()
		 */
		@Override
        public int hashCode()
        {
	        final int prime = 31;
	        int result = 1;
	        result = prime * result + this.id;
	        result = prime * result + this.pagenumber;
	        return result;
        }

		/*
		 * (non-Javadoc)
		 * @see java.lang.Object#equals(java.lang.Object)
		 */
		@Override
        public boolean equals(Object obj)
        {
	        if (this == obj)
		        return true;
	        if (obj == null)
		        return false;
	        if (getClass() != obj.getClass())
		        return false;
	        EntryId other = (EntryId) obj;
	        if (this.id != other.id)
		        return false;
	        if (this.pagenumber != other.pagenumber)
		        return false;
	        return true;
        }		
	}
}
