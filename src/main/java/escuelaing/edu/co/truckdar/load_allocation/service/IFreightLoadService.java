package escuelaing.edu.co.truckdar.load_allocation.service;

import escuelaing.edu.co.truckdar.load_allocation.dto.request.AssignDriverRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.request.CancelLoadRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.request.CreateFreightLoadRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.request.DeliveryConfirmationRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.response.DriverCommitmentResponse;
import escuelaing.edu.co.truckdar.load_allocation.dto.response.FreightLoadResponse;
import escuelaing.edu.co.truckdar.load_allocation.dto.response.ProofOfDeliveryResponse;
import escuelaing.edu.co.truckdar.load_allocation.model.LoadStatus;
import escuelaing.edu.co.truckdar.load_allocation.model.TruckTypeRequired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface IFreightLoadService {
    FreightLoadResponse publishLoad(CreateFreightLoadRequest request, UUID correlationId, String authenticatedUserId,
                                    String authenticatedRole);
    FreightLoadResponse assignDriver(Long loadId, AssignDriverRequest request);
    FreightLoadResponse notifyAssignment(Long loadId);
    boolean applyAssignmentNotified(Long loadId, String notifiedDriverId);
    boolean proposeReassignment(Long loadId, String driverId, String driverName, String vehiclePlate);
    boolean markNoDriversAvailable(Long loadId);
    boolean expireIfUnanswered(Long loadId, LocalDateTime notifiedBefore);
    FreightLoadResponse acceptAssignment(Long loadId, String authenticatedUserId);
    FreightLoadResponse rejectAssignment(Long loadId, String authenticatedUserId);
    FreightLoadResponse timeoutAssignment(Long loadId);
    FreightLoadResponse startTrip(Long loadId, String authenticatedUserId);
    FreightLoadResponse deliverLoad(Long loadId, String authenticatedUserId, DeliveryConfirmationRequest pod);
    FreightLoadResponse cancelLoad(Long loadId, String authenticatedUserId, String authenticatedRole, CancelLoadRequest request);
    List<FreightLoadResponse> getLoadsForDriver(String authenticatedUserId);
    List<FreightLoadResponse> listLoads(List<LoadStatus> statuses, String authenticatedUserId, String authenticatedRole);
    List<DriverCommitmentResponse> getDriverCommitments();
    ProofOfDeliveryResponse getProofOfDelivery(Long loadId, String authenticatedUserId, String authenticatedRole);
    FreightLoadResponse getLoadById(Long loadId);
    List<FreightLoadResponse> getAvailableLoads(TruckTypeRequired truckType);
    List<FreightLoadResponse> getLoadsByCompany(String generatorCompanyId, String authenticatedUserId, String authenticatedRole);
    List<FreightLoadResponse> getPublishedLoads(String authenticatedUserId);
}