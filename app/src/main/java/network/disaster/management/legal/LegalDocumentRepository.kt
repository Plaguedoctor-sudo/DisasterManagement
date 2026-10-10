package network.disaster.management.legal

object LegalDocumentRepository {

    val UNIFIED_TERMS_AND_DISCLAIMERS = """
        ================================================================================
                    CIVIL PROTECTION & DISASTER MANAGEMENT UTILITY
                  OPERATIONAL SPECIFICATION & END-USER LICENSE AGREEMENT
        ================================================================================
        Build Reference: DM-CIVIL-REL-4 | Classification: Public Safety Utility

        1. PURPOSE & FIELD OF OPERATION
        --------------------------------------------------------------------------------
        1.1 Emergency Information Management: This software functions as an auxiliary, 
            decentralized ad-hoc telemetry and data relay intended for deployment during 
            severe meteorological events (cyclones, floods), geophysical disasters 
            (earthquakes, landslides), industrial structural collapses, or severe municipal 
            telecommunication infrastructure failures.
        1.2 Civilian Safety & Hazard Coordination: Permitted utility functions comprise 
            the dissemination of medical assistance requests, missing-person status reports, 
            evacuation pathway coordinates, structural integrity assessments, and localized 
            hazard warnings.

        2. TELECOMMUNICATION & SPECTRUM CONFORMANCE
        --------------------------------------------------------------------------------
        2.1 Unlicensed Band Compliance: Wireless data dissemination is strictly confined 
            to unlicensed Industrial, Scientific, and Medical (ISM) spectrum bands 
            (2.4 GHz and 5 GHz) and native acoustic/optical transducers.
        2.2 Public Safety Interoperability: The software acts as an autonomous edge-node 
            application. It does not emulate commercial cellular base stations, modify 
            hardware serials (IMEI), or interfere with public safety radio frequencies.
        2.3 Ad-Hoc Store-and-Forward Relaying: To ensure continuity of communication 
            across fragmented disaster zones, intermediate devices act as automated data 
            relays. All transit packets use authenticated end-to-end payload encapsulation 
            to guarantee that intermediary nodes cannot access or alter community data.

        3. DATA RECORD INTEGRITY & LOGGING
        --------------------------------------------------------------------------------
        3.1 Cryptographic Audit Trails: Field logs, sensor readings, and incident reports 
            are secured using deterministic hashing and digital signatures to prevent 
            data corruption, loss of record integrity, or payload tampering during transit.
        3.2 Automated Resource Limits: The utility enforces dynamic storage quotas to 
            prevent large file transfers from saturating flash storage on participant 
            devices. High-priority distress packets are prioritized automatically.

        4. GENERAL DISCLAIMERS & LIABILITY LIMITATIONS
        --------------------------------------------------------------------------------
        4.1 Auxiliary Nature: This utility is an auxiliary, experimental communication 
            tool. It does not replace official emergency services, governmental public 
            warning systems, or direct rescue personnel directives.
        4.2 Non-Commercial Deployment: This software is distributed without fee or commercial 
            licensing obligations for civilian relief and community protection purposes.
    """.trimIndent()
}
