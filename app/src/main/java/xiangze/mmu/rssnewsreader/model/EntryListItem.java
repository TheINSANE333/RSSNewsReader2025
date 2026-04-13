package xiangze.mmu.rssnewsreader.model;

/**
 * Lean projection for the entries list to prevent OutOfMemoryError.
 * Extends EntryInfo to maintain compatibility where needed, but the Dao 
 * will only populate specific fields.
 */
public class EntryListItem extends EntryInfo {
    // No extra fields needed, we just use this as a marker for the lean projection
    // or to add specific fields only for the list if needed.
}
