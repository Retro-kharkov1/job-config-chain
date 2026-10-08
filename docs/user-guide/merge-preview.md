# Merge preview

[User guide](README.md) · [Merging and arrays (concept)](../concepts/merging-and-arrays.md)

On the job page the editor shows three panels that update live (debounced) while you edit: **Merged bases**
(read-only), the job's **Override**, and the **Merged result** (read-only). The result is computed with RFC 7396
Merge Patch, so you can check the effective configuration, including arrays replaced wholesale, before saving.

![Three-panel live merge preview: merged bases, own override and merged result](merge-preview.png)
*The debounced three-panel merge preview: merged bases, own override, merged result.*

Use it while editing a base-chain entry or the override ([Job page](job-page.md)).
