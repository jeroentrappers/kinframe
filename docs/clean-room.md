# Clean-room process

kinframe is an independent, open-source alternative to Frameo. It is built using a clean-room process:

1. **Analysis ("dirty room")**: Public material (website, help center, store listings, release notes) and the
   behaviour of the Frameo apps were studied for interoperability and feature analysis. Raw material (APKs,
   decompiled sources) is kept outside this repository and is never published.
2. **Specification**: Analysts write a functional specification in their own words in `docs/spec/`. It describes
   *what* the product does and how it behaves for the user. It contains no source code, no copied strings, and no
   copied assets, layouts, or images from Frameo.
3. **Implementation ("clean room")**: kinframe is implemented only from `docs/spec/` and our own design
   documents. Implementers do not consult decompiled Frameo code.

Frameo is a trademark of its respective owner. kinframe is not affiliated with or endorsed by Frameo.
